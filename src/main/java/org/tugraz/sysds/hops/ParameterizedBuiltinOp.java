/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.tugraz.sysds.hops;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import org.tugraz.sysds.hops.rewrite.HopRewriteUtils;
import org.tugraz.sysds.lops.Aggregate;
import org.tugraz.sysds.lops.Data;
import org.tugraz.sysds.lops.GroupedAggregate;
import org.tugraz.sysds.lops.GroupedAggregateM;
import org.tugraz.sysds.lops.Lop;
import org.tugraz.sysds.lops.ParameterizedBuiltin;
import org.tugraz.sysds.lops.LopProperties.ExecType;
import org.tugraz.sysds.parser.Statement;
import org.tugraz.sysds.parser.Expression.DataType;
import org.tugraz.sysds.parser.Expression.ValueType;
import org.tugraz.sysds.runtime.matrix.MatrixCharacteristics;
import org.tugraz.sysds.runtime.util.UtilFunctions;


/**
 * Defines the HOP for calling an internal function (with custom parameters) from a DML script. 
 * 
 */
public class ParameterizedBuiltinOp extends MultiThreadedHop
{
	public static boolean FORCE_DIST_RM_EMPTY = false;

	//operator type
	private ParamBuiltinOp _op;

	//removeEmpty hints
	private boolean _outputPermutationMatrix = false;
	private boolean _bRmEmptyBC = false;
	
	/**
	 * List of "named" input parameters. They are maintained as a hashmap:
	 * parameter names (String) are mapped as indices (Integer) into getInput()
	 * arraylist.
	 * 
	 * i.e., getInput().get(_paramIndexMap.get(parameterName)) refers to the Hop
	 * that is associated with parameterName.
	 */
	private HashMap<String, Integer> _paramIndexMap = new HashMap<>();

	private ParameterizedBuiltinOp() {
		//default constructor for clone
	}

	/**
	 * Creates a new HOP for a function call
	 * 
	 * @param l ?
	 * @param dt data type
	 * @param vt value type
	 * @param op the ParamBuiltinOp
	 * @param inputParameters map of input parameters
	 */
	public ParameterizedBuiltinOp(String l, DataType dt, ValueType vt,
			ParamBuiltinOp op, LinkedHashMap<String, Hop> inputParameters) {
		super(l, dt, vt);
		
		_op = op;
		
		int index = 0;
		for( Entry<String,Hop> e : inputParameters.entrySet() ) {
			String s = e.getKey();
			Hop input = e.getValue();
			getInput().add(input);
			input.getParent().add(this);
			_paramIndexMap.put(s, index);
			index++;
		}
		
		//compute unknown dims and nnz
		refreshSizeInformation();
	}

	@Override
	public void checkArity() {
		int sz = _input.size();
		int pz = _paramIndexMap.size();
		HopsException.check(sz == pz, this, "has %d inputs but %d parameters", sz, pz);
	}

	public HashMap<String, Integer> getParamIndexMap(){
		return _paramIndexMap;
	}
	
	@Override
	public String getOpString() {
		return "" + _op;
	}
	
	public ParamBuiltinOp getOp() {
		return _op;
	}
	
	public void setOutputPermutationMatrix(boolean flag) {
		_outputPermutationMatrix = flag;
	}
	
	public Hop getTargetHop() {
		return getParameterHop("target");
	}
	
	public Hop getParameterHop(String name) {
		return _paramIndexMap.containsKey(name) ?
			getInput().get(_paramIndexMap.get(name)) : null;
	}
	
	@Override
	public boolean isGPUEnabled() {
		return false;
	}
	
	@Override
	public Lop constructLops() 
	{
		//return already created lops
		if( getLops() != null )
			return getLops();
		
		// construct lops for all input parameters
		HashMap<String, Lop> inputlops = new HashMap<>();
		for (Entry<String, Integer> cur : _paramIndexMap.entrySet())
			inputlops.put(cur.getKey(), getInput().get(cur.getValue()).constructLops());

		switch( _op ) {
			case GROUPEDAGG: { 
				ExecType et = optFindExecType();
				constructLopsGroupedAggregate(inputlops, et);
				break;
			}
			case RMEMPTY: {
				ExecType et = optFindExecType();
				constructLopsRemoveEmpty(inputlops, et);
				break;
			} 
			case REXPAND: {
				ExecType et = optFindExecType();
				constructLopsRExpand(inputlops, et);
				break;
			} 
			case CDF:
			case INVCDF: 
			case REPLACE:
			case LOWER_TRI:
			case UPPER_TRI:
			case TRANSFORMAPPLY:
			case TRANSFORMDECODE:
			case TRANSFORMCOLMAP:
			case TRANSFORMMETA:
			case TOSTRING:
			case PARAMSERV:
			case LIST: {
				ExecType et = optFindExecType();
				ParameterizedBuiltin pbilop = new ParameterizedBuiltin(inputlops,
					HopsParameterizedBuiltinLops.get(_op), getDataType(), getValueType(), et);
				setOutputDimensions(pbilop);
				setLineNumbers(pbilop);
				setLops(pbilop);
				break;
			}
			default:
				throw new HopsException("Unknown ParamBuiltinOp: "+_op);
		}
		
		//add reblock/checkpoint lops if necessary
		constructAndSetLopsDataFlowProperties();
		
		return getLops();
	}
	
	private void constructLopsGroupedAggregate(HashMap<String, Lop> inputlops, ExecType et) 
	{
		//reset reblock requirement (see MR aggregate / construct lops)
		setRequiresReblock( false );
		
		//determine output dimensions
		long outputDim1=-1, outputDim2=-1;
		Lop numGroups = inputlops.get(Statement.GAGG_NUM_GROUPS);
		if ( !dimsKnown() && numGroups != null && numGroups instanceof Data && ((Data)numGroups).isLiteral() ) {
			long ngroups = ((Data)numGroups).getLongValue();
			
			Lop input = inputlops.get(GroupedAggregate.COMBINEDINPUT);
			long inDim1 = input.getOutputParameters().getNumRows();
			long inDim2 = input.getOutputParameters().getNumCols();
			boolean rowwise = (inDim1==1 && inDim2 > 1 );
			
			if( rowwise ) { //vector
				outputDim1 = ngroups;
				outputDim2 = 1;
			}
			else { //vector or matrix
				outputDim1 = inDim2;
				outputDim2 = ngroups;
			}
		}
		
		//construct lops
		//CP/Spark 
		{
			Lop grp_agg = null;
			
			if( et == ExecType.CP) 
			{
				int k = OptimizerUtils.getConstrainedNumThreads( _maxNumThreads );
				grp_agg = new GroupedAggregate(inputlops, getDataType(), getValueType(), et, k);
				grp_agg.getOutputParameters().setDimensions(outputDim1, outputDim2, getRowsInBlock(), getColsInBlock(), -1);
			}
			else if(et == ExecType.SPARK) 
			{
				//physical operator selection
				Hop groups = getParameterHop(Statement.GAGG_GROUPS);
				boolean broadcastGroups = (_paramIndexMap.get(Statement.GAGG_WEIGHTS) == null &&
						OptimizerUtils.checkSparkBroadcastMemoryBudget( groups.getDim1(), groups.getDim2(), 
								groups.getRowsInBlock(), groups.getColsInBlock(), groups.getNnz()) );
				
				if( broadcastGroups //mapgroupedagg
					&& getParameterHop(Statement.GAGG_FN) instanceof LiteralOp
					&& ((LiteralOp)getParameterHop(Statement.GAGG_FN)).getStringValue().equals("sum")
					&& inputlops.get(Statement.GAGG_NUM_GROUPS) != null ) 
				{
					Hop target = getTargetHop();
					grp_agg = new GroupedAggregateM(inputlops, getDataType(), getValueType(), true, ExecType.SPARK);
					grp_agg.getOutputParameters().setDimensions(outputDim1, outputDim2, target.getRowsInBlock(), target.getColsInBlock(), -1);
					//no reblock required (directly output binary block)
				}
				else //groupedagg (w/ or w/o broadcast)
				{
					grp_agg = new GroupedAggregate(inputlops, getDataType(), getValueType(), et, broadcastGroups);
					grp_agg.getOutputParameters().setDimensions(outputDim1, outputDim2, -1, -1, -1);
					setRequiresReblock( true );	
				}
			}
			
			setLineNumbers(grp_agg);
			setLops(grp_agg);
		}
	}

	private void constructLopsRemoveEmpty(HashMap<String, Lop> inputlops, ExecType et) 
	{
		Hop targetHop = getTargetHop();
		Hop marginHop = getParameterHop("margin");
		Hop selectHop = getParameterHop("select");
		
		if( et == ExecType.CP )
		{
			ParameterizedBuiltin pbilop = new ParameterizedBuiltin(inputlops,HopsParameterizedBuiltinLops.get(_op), getDataType(), getValueType(), et);
			setOutputDimensions(pbilop);
			setLineNumbers(pbilop);
			setLops(pbilop);
			
			/*DISABLED CP PMM (see for example, MDA Bivar test, requires size propagation on recompile)
			if( et == ExecType.CP && isTargetDiagInput() && marginHop instanceof LiteralOp 
					 && ((LiteralOp)marginHop).getStringValue().equals("rows")
					 && _outputPermutationMatrix ) //SPECIAL CASE SELECTION VECTOR
			{				
				//TODO this special case could be taken into account for memory estimates in order
				// to reduce the estimates for the input diag and subsequent matrix multiply
				
				//get input vector (without materializing diag())
				Hop input = targetHop.getInput().get(0);
				long brlen = input.getRowsInBlock();
				long bclen = input.getColsInBlock();
				MemoTable memo = new MemoTable();
			
				boolean isPPredInput = (input instanceof BinaryOp && ((BinaryOp)input).isPPredOperation());
				
				//step1: compute index vectors
				Hop ppred0 = input;
				if( !isPPredInput ) { //ppred only if required
					ppred0 = new BinaryOp("tmp1", DataType.MATRIX, ValueType.DOUBLE, OpOp2.NOTEQUAL, input, new LiteralOp("0",0));
					HopRewriteUtils.setOutputBlocksizes(ppred0, brlen, bclen);
					ppred0.refreshSizeInformation();
					ppred0.computeMemEstimate(memo); //select exec type
					HopRewriteUtils.copyLineNumbers(this, ppred0);
				}
				
				UnaryOp cumsum = new UnaryOp("tmp2", DataType.MATRIX, ValueType.DOUBLE, OpOp1.CUMSUM, ppred0); 
				HopRewriteUtils.setOutputBlocksizes(cumsum, brlen, bclen);
				cumsum.refreshSizeInformation(); 
				cumsum.computeMemEstimate(memo); //select exec type
				HopRewriteUtils.copyLineNumbers(this, cumsum);	
			
				BinaryOp sel = new BinaryOp("tmp3", DataType.MATRIX, ValueType.DOUBLE, OpOp2.MULT, ppred0, cumsum);
				HopRewriteUtils.setOutputBlocksizes(sel, brlen, bclen); 
				sel.refreshSizeInformation();
				sel.computeMemEstimate(memo); //select exec type
				HopRewriteUtils.copyLineNumbers(this, sel);
				Lop loutput = sel.constructLops();
				
				//Step 4: cleanup hops (allow for garbage collection)
				HopRewriteUtils.removeChildReference(ppred0, input);
				
				setLops( loutput );
			}
			else //GENERAL CASE
			{
				ParameterizedBuiltin pbilop = new ParameterizedBuiltin( et, inputlops,
						HopsParameterizedBuiltinLops.get(_op), getDataType(), getValueType());
				
				pbilop.getOutputParameters().setDimensions(getDim1(),getDim2(), getRowsInBlock(), getColsInBlock(), getNnz());
				setLineNumbers(pbilop);
				setLops(pbilop);
			}
			*/
		}
		else if( et == ExecType.SPARK )
		{
			if( !(marginHop instanceof LiteralOp) )
				throw new HopsException("Parameter 'margin' must be a literal argument.");
			
			Hop input = targetHop;
			long rlen = input.getDim1();
			long clen = input.getDim2();
			int brlen = input.getRowsInBlock();
			int bclen = input.getColsInBlock();
			boolean rmRows = ((LiteralOp)marginHop).getStringValue().equals("rows");
			
			//construct lops via new partial hop dag and subsequent lops construction 
			//in order to reuse of operator selection decisions
			BinaryOp ppred0 = null;
			Hop emptyInd = null;
			
			if(selectHop == null) {
				//Step1: compute row/col non-empty indicators 
				ppred0 = HopRewriteUtils.createBinary(input, new LiteralOp(0), OpOp2.NOTEQUAL);
				ppred0.setForcedExecType(ExecType.SPARK); //always Spark
				
				emptyInd = ppred0;
				if( !((rmRows && clen == 1) || (!rmRows && rlen==1)) ){
					emptyInd = HopRewriteUtils.createAggUnaryOp(ppred0, AggOp.MAX, rmRows?Direction.Row:Direction.Col);
					emptyInd.setForcedExecType(ExecType.SPARK); //always Spark
				}
			} 
			else {
				emptyInd = selectHop;
			}
			
			//Step 2: compute row offsets for non-empty rows
			Hop cumsumInput = emptyInd;
			if( !rmRows ){
				cumsumInput = HopRewriteUtils.createTranspose(emptyInd);
				HopRewriteUtils.updateHopCharacteristics(cumsumInput, brlen, bclen, this);
			}
		
			UnaryOp cumsum = HopRewriteUtils.createUnary(cumsumInput, OpOp1.CUMSUM); 
			HopRewriteUtils.updateHopCharacteristics(cumsum, brlen, bclen, this);
		
			Hop cumsumOutput = cumsum;
			if( !rmRows ){
				cumsumOutput = HopRewriteUtils.createTranspose(cumsum);
				HopRewriteUtils.updateHopCharacteristics(cumsumOutput, brlen, bclen, this);	
			}
			
			Hop maxDim = HopRewriteUtils.createAggUnaryOp(cumsumOutput, AggOp.MAX, Direction.RowCol); //alternative: right indexing
			HopRewriteUtils.updateHopCharacteristics(maxDim, brlen, bclen, this);
			
			BinaryOp offsets = HopRewriteUtils.createBinary(cumsumOutput, emptyInd, OpOp2.MULT);
			HopRewriteUtils.updateHopCharacteristics(offsets, brlen, bclen, this);
			
			//Step 3: gather non-empty rows/cols into final results 
			Lop linput = input.constructLops();
			Lop loffset = offsets.constructLops();
			Lop lmaxdim = maxDim.constructLops();
			
			HashMap<String, Lop> inMap = new HashMap<>();
			inMap.put("target", linput);
			inMap.put("offset", loffset);
			inMap.put("maxdim", lmaxdim);
			inMap.put("margin", inputlops.get("margin"));
			inMap.put("empty.return", inputlops.get("empty.return"));
			
			if ( !FORCE_DIST_RM_EMPTY && isRemoveEmptyBcSP())
				_bRmEmptyBC = true;
			
			ParameterizedBuiltin pbilop = new ParameterizedBuiltin( inMap, HopsParameterizedBuiltinLops.get(_op), getDataType(), getValueType(), et, _bRmEmptyBC);			
			setOutputDimensions(pbilop);
			setLineNumbers(pbilop);
			
			//Step 4: cleanup hops (allow for garbage collection)
			if(selectHop == null)
				HopRewriteUtils.removeChildReference(ppred0, input);
			
			setLops(pbilop);
			
			//NOTE: in contrast to mr, replication and aggregation handled instruction-local
		}
	}

	private void constructLopsRExpand(HashMap<String, Lop> inputlops, ExecType et) 
	{
		int k = OptimizerUtils.getConstrainedNumThreads( _maxNumThreads );
		ParameterizedBuiltin pbilop = new ParameterizedBuiltin(inputlops, 
				HopsParameterizedBuiltinLops.get(_op), getDataType(), getValueType(), et, k);
		setOutputDimensions(pbilop);
		setLineNumbers(pbilop);
		setLops(pbilop);
	}

	@Override
	protected double computeOutputMemEstimate( long dim1, long dim2, long nnz )
	{	
		if (getOp() == ParamBuiltinOp.TOSTRING){
			// Conservative Assumptions about characteristics of digits
			final long AVERAGE_CHARS_PER_VALUE = 7;
			final long AVERAGE_CHARS_PER_INDEX = 4;
			
			// Default Values for toString
			long specifiedRows = 100;
			long specifiedCols = 100;
			boolean sparsePrint = false;
			String sep = " ";
			String linesep = "\n";
			
			Hop rowsHop = getParameterHop("rows");
			Hop colsHop = getParameterHop("cols");
			Hop sparsePrintHOP = getParameterHop("sparse");
			Hop sepHop = getParameterHop("sep");
			Hop linesepHop = getParameterHop("linesep");
			
			long numNonZeroes = getInput().get(0).getNnz();
			if (numNonZeroes < 0)
				numNonZeroes = specifiedRows * specifiedCols;
			long numRows = getInput().get(0).getDim1();
			if (numRows < 0) 	// If number of rows is not known, set to default
				numRows = specifiedRows;
			long numCols = getInput().get(0).getDim2();
			if (numCols < 0)	// If number of columns is not known, set to default
				numCols = specifiedCols;
			
			// Assume Defaults : 100 * 100, sep = " ", linesep = "\n", sparse = false
			// String size in bytes is 36 + number_of_chars * 2
			final long DEFAULT_SIZE = 36 + 2 *
					(100 * 100 * AVERAGE_CHARS_PER_VALUE 	// Length for digits  
					+ 1 * 100 * 99 							// Length for separator chars
					+ 1* 100) ;								// Length for line separator chars
			
			try {
			
				if (rowsHop != null && rowsHop instanceof LiteralOp) {
					specifiedRows = ((LiteralOp)rowsHop).getLongValue();
				}
				numRows = numRows < specifiedRows ? numRows : specifiedRows;
				if (colsHop != null && colsHop instanceof LiteralOp){
					specifiedCols = ((LiteralOp)colsHop).getLongValue();
				}
				numCols = numCols < specifiedCols ? numCols : specifiedCols;
				
				if (sparsePrintHOP != null && sparsePrintHOP instanceof LiteralOp){
					sparsePrint = ((LiteralOp)sparsePrintHOP).getBooleanValue();
				}
				
				if (sepHop != null && sepHop instanceof LiteralOp){
					sep = ((LiteralOp)sepHop).getStringValue();
				}
				
				if (linesepHop != null && linesepHop instanceof LiteralOp){
					linesep = ((LiteralOp)linesepHop).getStringValue();
				}
				
				long numberOfChars = -1;
				
				if (sparsePrint){
					numberOfChars = AVERAGE_CHARS_PER_VALUE * numNonZeroes			// Length for value digits
									+ AVERAGE_CHARS_PER_INDEX * 2L * numNonZeroes	// Length for row & column index
									+ sep.length() * 2L * numNonZeroes				// Length for separator chars
									+ linesep.length() * numNonZeroes;				// Length for line separator chars
				} else {
					numberOfChars = AVERAGE_CHARS_PER_VALUE * numRows * numCols 	// Length for digits
									+ sep.length() * numRows * (numCols - 1) 		// Length for separator chars
									+ linesep.length() * numRows;					// Length for line separator chars
				}
				
				/*
				 * For JVM
				 * 8 + // object header used by the VM
				 * 8 + // 64-bit reference to char array (value)
				 * 8 + string.length() * 2 + // character array itself (object header + 16-bit chars)
				 * 4 + // offset integer
				 * 4 + // count integer
				 * 4 + // cached hash code
				 */
				
				return (36 + numberOfChars * 2);
				
			} catch (HopsException e){
				LOG.warn("Invalid values when trying to compute dims1, dims2 & nnz", e);
				
				return DEFAULT_SIZE;
			}
			
		} else {
			double sparsity = OptimizerUtils.getSparsity(dim1, dim2, nnz);
			return OptimizerUtils.estimateSizeExactSparsity(dim1, dim2, sparsity);
		}
	}
	
	@Override
	protected double computeIntermediateMemEstimate( long dim1, long dim2, long nnz )
	{
		double ret = 0;
		
		if( _op == ParamBuiltinOp.RMEMPTY )
		{ 
			Hop marginHop = getParameterHop("margin");
			boolean cols =  marginHop instanceof LiteralOp 
					&& "cols".equals(((LiteralOp)marginHop).getStringValue());
			
			//remove empty has additional internal memory requirements for 
			//computing selection vectors
			if( cols )
			{
				//selection vector: boolean array in the number of columns 
				ret += OptimizerUtils.BOOLEAN_SIZE * dim2;
				
				//removeEmpty-cols has additional memory requirements for intermediate 
				//data structures in order to make this a cache-friendly operation.
				ret += OptimizerUtils.INT_SIZE * dim2;
			}
			else //rows
			{
				//selection vector: boolean array in the number of rows 
				ret += OptimizerUtils.BOOLEAN_SIZE * dim1;
			}
		}
		else if( _op == ParamBuiltinOp.REXPAND )
		{
			Hop dir = getParameterHop("dir");
			String dirVal = ((LiteralOp)dir).getStringValue();
			if( "rows".equals(dirVal) )
			{
				//rexpand w/ rows direction has additional memory requirements for 
				//intermediate data structures in order to prevent performance issues
				//due to random output row access (to make this cache-friendly)
				//NOTE: bounded by blocksize configuration: at most 12MB
				ret = (OptimizerUtils.DOUBLE_SIZE + OptimizerUtils.INT_SIZE) 
						* Math.min(dim1, 1024*1024);
			}
		}
		
		return ret;
	}
	
	@Override
	protected long[] inferOutputCharacteristics( MemoTable memo )
	{
		//Notes: CDF, TOSTRING always known because scalar outputs
		
		long[] ret = null;
	
		Hop input = getTargetHop();	
		MatrixCharacteristics mc = memo.getAllInputStats(input);

		if( _op == ParamBuiltinOp.GROUPEDAGG ) 
		{
			// Get the number of groups provided as part of aggregate() invocation, whenever available.
			if ( _paramIndexMap.get(Statement.GAGG_NUM_GROUPS) != null ) {
				Hop ngroups = getParameterHop(Statement.GAGG_NUM_GROUPS);
				if(ngroups != null && ngroups instanceof LiteralOp) {
					long m = HopRewriteUtils.getIntValueSafe((LiteralOp)ngroups);
					long n = (mc.getRows()==1)?1:mc.getCols();
					return new long[]{m, n, m};
				}
			}
			
			// Output dimensions are completely data dependent. In the worst case, 
			// #groups = #rows in the grouping attribute (e.g., categorical attribute is an ID column, say EmployeeID).
			// In such a case, #rows in the output = #rows in the input. Also, output sparsity is 
			// likely to be 1.0 (e.g., groupedAgg(groups=<a ID column>, fn="count"))
			long m = mc.getRows();
			long n = (mc.getRows()==1)?1:mc.getCols();
			if ( m >= 1 ) {
				ret = new long[]{m, n, m};
			}
		}
		else if(   _op == ParamBuiltinOp.RMEMPTY ) 
		{ 
			// similar to groupedagg because in the worst-case ouputsize eq inputsize
			// #nnz is exactly the same as in the input but sparsity can be higher if dimensions.
			// change (denser output).
			if ( mc.dimsKnown() ) {
				String margin = "rows";
				Hop marginHop = getParameterHop("margin");
				if(    marginHop instanceof LiteralOp 
						&& "cols".equals(((LiteralOp)marginHop).getStringValue()) )
					margin = new String("cols");
				
				MatrixCharacteristics mcSelect = null;
				if (_paramIndexMap.get("select") != null) {
					Hop select = getParameterHop("select");
					mcSelect = memo.getAllInputStats(select);
				}

				long lDim1 = 0, lDim2 = 0;
				if( margin.equals("rows") ) {
					lDim1 = (mcSelect == null ||  !mcSelect.nnzKnown() ) ? mc.getRows(): mcSelect.getNonZeros(); 
					lDim2 = mc.getCols();
				} else {
					lDim1 = mc.getRows();
					lDim2 = (mcSelect == null ||  !mcSelect.nnzKnown() ) ? mc.getCols(): mcSelect.getNonZeros(); 
				}
				ret = new long[]{lDim1, lDim2, mc.getNonZeros()};
			}
		}
		else if(   _op == ParamBuiltinOp.REPLACE ) 
		{ 
			// the worst-case estimate from the input directly propagates to the output 
			// #nnz depends on the replacement pattern and value, same as input if non-zero
			if ( mc.dimsKnown() )
			{
				if( isNonZeroReplaceArguments() )
					ret = new long[]{mc.getRows(), mc.getCols(), mc.getNonZeros()};
				else
					ret = new long[]{mc.getRows(), mc.getCols(), -1};
			}
		}
		else if( _op == ParamBuiltinOp.REXPAND )
		{
			//dimensions are exactly known from input, sparsity unknown but upper bounded by nrow(v)
			//note: cannot infer exact sparsity due to missing cast for outer and potential cutoff for table
			//but very good sparsity estimate possible (number of non-zeros in input)
			Hop max = getParameterHop("max");
			Hop dir = getParameterHop("dir");
			long maxVal = computeDimParameterInformation(max, memo);
			String dirVal = ((LiteralOp)dir).getStringValue();
			if( mc.dimsKnown() ) {
				long lnnz = mc.nnzKnown() ? mc.getNonZeros() : mc.getRows();
				if( "cols".equals(dirVal) ) { //expand horizontally
					ret = new long[]{mc.getRows(), maxVal, lnnz};
				}
				else if( "rows".equals(dirVal) ){ //expand vertically
					ret = new long[]{maxVal, mc.getRows(), lnnz};
				}	
			}
		}
		else if( _op == ParamBuiltinOp.TRANSFORMDECODE ) {
			if( mc.dimsKnown() ) {
				//rows: remain unchanged
				//cols: dummy coding might decrease never increase cols 
				return new long[]{mc.getRows(), mc.getCols(), mc.getRows()*mc.getCols()};
			}
		}
		else if( _op == ParamBuiltinOp.TRANSFORMAPPLY ) {
			if( mc.dimsKnown() ) {
				//rows: omitting might decrease but never increase rows
				//cols: dummy coding and binning might increase cols but nnz stays constant
				return new long[]{mc.getRows(), mc.getCols(), mc.getRows()*mc.getCols()};
			}
		}
		
		return ret;
	}
	
	@Override 
	public boolean allowsAllExecTypes()
	{
		return false;
	}
	
	@Override
	protected ExecType optFindExecType() 
	{
		checkAndSetForcedPlatform();
		
		if( _etypeForced != null )
		{
			_etype = _etypeForced;
		}
		else 
		{
			if ( OptimizerUtils.isMemoryBasedOptLevel() ) {
				_etype = findExecTypeByMemEstimate();
			}
			else if (   _op == ParamBuiltinOp.GROUPEDAGG
				&& getTargetHop().areDimsBelowThreshold() ) {
				_etype = ExecType.CP;
			}
			else {
				_etype = ExecType.SPARK;
			}
			
			//check for valid CP dimensions and matrix size
			checkAndSetInvalidCPDimsAndSize();
		}
		
		// 1. Force CP for in-memory only transform builtins.
		// 2. For paramserv function, always be CP mode so that
		// the parameter server could have a central instruction
		// to determine the local or remote workers
		if (_op == ParamBuiltinOp.TRANSFORMCOLMAP || _op == ParamBuiltinOp.TRANSFORMMETA
				|| _op == ParamBuiltinOp.TOSTRING || _op == ParamBuiltinOp.LIST
				|| _op == ParamBuiltinOp.CDF || _op == ParamBuiltinOp.INVCDF
				|| _op == ParamBuiltinOp.PARAMSERV) {
			_etype = ExecType.CP;
		}

		//mark for recompile (forever)
		setRequiresRecompileIfNecessary();
		
		return _etype;
	}
	
	@Override
	public void refreshSizeInformation()
	{
		switch( _op )
		{
			case CDF:
			case INVCDF:	
				//do nothing; CDF is a scalar
				break;
			
			case GROUPEDAGG: { 
				// output dimension dim1 is completely data dependent 
				long ldim1 = -1;
				if ( _paramIndexMap.get(Statement.GAGG_NUM_GROUPS) != null ) {
					Hop ngroups = getParameterHop(Statement.GAGG_NUM_GROUPS);
					if(ngroups != null && ngroups instanceof LiteralOp) {
						ldim1 = HopRewriteUtils.getIntValueSafe((LiteralOp)ngroups);
					}
				}
				
				Hop target = getTargetHop();
				long ldim2 = (target.getDim1()==1)?1:target.getDim2(); 
				
				setDim1( ldim1 );
				setDim2( ldim2 );
				break;
			}
			case RMEMPTY: {
				//one output dimension dim1 or dim2 is completely data dependent 
				Hop target = getTargetHop();
				Hop margin = getParameterHop("margin");
				if( margin instanceof LiteralOp ) {
					LiteralOp lmargin = (LiteralOp)margin;
					if( "rows".equals(lmargin.getStringValue()) )
						setDim2( target.getDim2() );
					else if( "cols".equals(lmargin.getStringValue()) )
						setDim1( target.getDim1() );
				}
				setNnz( target.getNnz() );
				break;
			}
			case LOWER_TRI:
			case UPPER_TRI: {
				Hop target = getTargetHop();
				setDim1(target.getDim1());
				setDim2(target.getDim2());
				break;
			}
			case REPLACE: {
				//dimensions are exactly known from input, sparsity might increase/decrease if pattern/replacement 0 
				Hop target = getTargetHop();
				setDim1( target.getDim1() );
				setDim2( target.getDim2() );
				if( isNonZeroReplaceArguments() )
					setNnz( target.getNnz() );
				
				break;
			}
			case REXPAND: {
				//dimensions are exactly known from input, sparsity unknown but upper bounded by nrow(v)
				//note: cannot infer exact sparsity due to missing cast for outer and potential cutoff for table
				Hop target = getTargetHop();
				Hop max = getParameterHop("max");
				Hop dir = getParameterHop("dir");
				double maxVal = computeSizeInformation(max);
				String dirVal = ((LiteralOp)dir).getStringValue();
				
				if( "cols".equals(dirVal) ) { //expand horizontally
					setDim1(target.getDim1());
					setDim2(UtilFunctions.toLong(maxVal));
				}
				else if( "rows".equals(dirVal) ){ //expand vertically
					setDim1(UtilFunctions.toLong(maxVal));
					setDim2(target.getDim1());
				}
				
				break;
			}
			case TRANSFORMDECODE: {
				Hop target = getTargetHop();
				//rows remain unchanged for recoding and dummy coding
				setDim1( target.getDim1() );
				//cols remain unchanged only if no dummy coding
				//TODO parse json spec
				break;
			}
			case TRANSFORMAPPLY: {
				//rows remain unchanged only if no omitting
				//cols remain unchanged of no dummy coding 
				//TODO parse json spec
				break;
			}
			case TRANSFORMCOLMAP: {
				Hop target = getTargetHop();
				setDim1( target.getDim2() );
				setDim2( 3 ); //fixed schema
				break;
			}
			case LIST: {
				setDim1( getInput().size() );
				setDim2(1);
				break;
			}
			default:
				//do nothing
				break;
		}
	}
	
	@Override
	@SuppressWarnings("unchecked")
	public Object clone() throws CloneNotSupportedException 
	{
		ParameterizedBuiltinOp ret = new ParameterizedBuiltinOp();	
		
		//copy generic attributes
		ret.clone(this, false);
		
		//copy specific attributes
		ret._op = _op;
		ret._outputEmptyBlocks = _outputEmptyBlocks;
		ret._outputPermutationMatrix = _outputPermutationMatrix;
		ret._paramIndexMap = (HashMap<String, Integer>) _paramIndexMap.clone();
		//note: no deep cp of params since read-only 
		
		return ret;
	}
	
	@Override
	public boolean compare( Hop that )
	{
		if( !(that instanceof ParameterizedBuiltinOp) )
			return false;
		
		ParameterizedBuiltinOp that2 = (ParameterizedBuiltinOp)that;	
		boolean ret = (_op == that2._op
					  && _paramIndexMap!=null && that2._paramIndexMap!=null
					  && _paramIndexMap.size() == that2._paramIndexMap.size()
					  && _outputEmptyBlocks == that2._outputEmptyBlocks
					  && _outputPermutationMatrix == that2._outputPermutationMatrix );
		if( ret )
		{
			for( Entry<String,Integer> e : _paramIndexMap.entrySet() )
			{
				String key1 = e.getKey();
				int pos1 = e.getValue();
				int pos2 = that2._paramIndexMap.get(key1);
				ret &= (   that2.getInput().get(pos2)!=null
					    && getInput().get(pos1) == that2.getInput().get(pos2) );
			}
		}
		
		return ret;
	}

	@Override
	public boolean isTransposeSafe()
	{
		boolean ret = false;
		
		try
		{
			if( _op == ParamBuiltinOp.GROUPEDAGG )
			{
				int ix = _paramIndexMap.get(Statement.GAGG_FN);
				Hop fnHop = getInput().get(ix);
				ret = (fnHop instanceof LiteralOp && Statement.GAGG_FN_SUM.equals(((LiteralOp)fnHop).getStringValue()) );
			}
		}
		catch(Exception ex) {
			//silent false
			LOG.warn("Check for transpose-safeness failed, continue assuming false.", ex);
		}
		
		return ret;	
	}

	public boolean isCountFunction()
	{
		boolean ret = false;
		
		try {
			if( _op == ParamBuiltinOp.GROUPEDAGG ) {
				Hop fnHop = getParameterHop(Statement.GAGG_FN);
				ret = (fnHop instanceof LiteralOp && Statement.GAGG_FN_COUNT.equals(((LiteralOp)fnHop).getStringValue()) );
			}
		}
		catch(Exception ex){
			LOG.warn("Check for count function failed, continue assuming false.", ex);
		}
		
		return ret;
	}
	
	/**
	 * Only applies to REPLACE.
	 * @return true if non-zero replace arguments
	 */
	private boolean isNonZeroReplaceArguments()
	{
		boolean ret = false;
		try 
		{
			Hop pattern = getParameterHop("pattern");
			Hop replace = getParameterHop("replacement");
			if( pattern instanceof LiteralOp && ((LiteralOp)pattern).getDoubleValue()!=0d &&
			    replace instanceof LiteralOp && ((LiteralOp)replace).getDoubleValue()!=0d )
			{
				ret = true;
			}
		}
		catch(Exception ex) {
			LOG.warn(ex.getMessage());
		}
		
		return ret;
	}

	public boolean isTargetDiagInput()
	{
		Hop targetHop = getTargetHop();
		
		//input vector (guarantees diagV2M), implies remove rows
		return (   targetHop instanceof ReorgOp 
				&& ((ReorgOp)targetHop).getOp()==ReOrgOp.DIAG 
				&& targetHop.getInput().get(0).getDim2() == 1 ); 
	}

	/**
	 * This will check if there is sufficient memory locally (twice the size of second matrix, for original and sort data), and remotely (size of second matrix (sorted data)).  
	 * @return true if sufficient memory locally
	 */
	private boolean isRemoveEmptyBcSP()	// TODO find if 2 x size needed. 
	{
		boolean ret = false;
		Hop input = getInput().get(0);
		
		//note: both cases (partitioned matrix, and sorted double array), require to
		//fit the broadcast twice into the local memory budget. Also, the memory 
		//constraint only needs to take the rhs into account because the output is 
		//guaranteed to be an aggregate of <=16KB
		
		double size = input.dimsKnown() ? 
				OptimizerUtils.estimateSize(input.getDim1(), 1) : //dims known and estimate fits
					input.getOutputMemEstimate();                 //dims unknown but worst-case estimate fits
		
		if( OptimizerUtils.checkSparkBroadcastMemoryBudget(size) ) {
			ret = true;
		}
		
		return ret;
	}
	
}