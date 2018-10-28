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

package org.tugraz.sysds.runtime.io;

import java.io.IOException;

import org.tugraz.sysds.runtime.matrix.data.MatrixBlock;

/**
 * Base class for all format-specific matrix writers. Every writer is required to implement the basic 
 * write functionality but might provide additional custom functionality. Any non-default parameters
 * (e.g., CSV read properties) should be passed into custom constructors. There is also a factory
 * for creating format-specific writers. 
 * 
 */
public abstract class MatrixWriter 
{
	public void writeMatrixToHDFS( MatrixBlock src, String fname, long rlen, long clen, int brlen, int bclen, long nnz )
		throws IOException
	{
		writeMatrixToHDFS(src, fname, rlen, clen, brlen, bclen, nnz, false);
	}

	public abstract void writeMatrixToHDFS( MatrixBlock src, String fname, long rlen, long clen, int brlen, int bclen, long nnz, boolean diag )
		throws IOException;
	
	
	/**
	 * Writes a minimal entry to represent an empty matrix on hdfs.
	 * 
	 * @param fname file name
	 * @param rlen number of rows
	 * @param clen number of columns
	 * @param brlen number of rows in block
	 * @param bclen number of columns in block
	 * @throws IOException if IOException occurs
	 */
	public abstract void writeEmptyMatrixToHDFS( String fname, long rlen, long clen, int brlen, int bclen )
		throws IOException;

	public static MatrixBlock[] createMatrixBlocksForReuse( long rlen, long clen, int brlen, int bclen, boolean sparse, long nonZeros ) {
		MatrixBlock[] blocks = new MatrixBlock[4];
		double sparsity = ((double)nonZeros)/(rlen*clen);
		long estNNZ = -1;
		
		//full block 
		if( rlen >= brlen && clen >= bclen ) {
			estNNZ = (long) (brlen*bclen*sparsity);
			blocks[0] = new MatrixBlock( brlen, bclen, sparse, (int)estNNZ );
		}
		//partial col block
		if( rlen >= brlen && clen%bclen!=0 ) {
			estNNZ = (long) (brlen*(clen%bclen)*sparsity);
			blocks[1] = new MatrixBlock( brlen, (int)(clen%bclen), sparse, (int)estNNZ );
		}
		//partial row block
		if( rlen%brlen!=0 && clen>=bclen ) {
			estNNZ = (long) ((rlen%brlen)*bclen*sparsity);
			blocks[2] = new MatrixBlock( (int)(rlen%brlen), bclen, sparse, (int)estNNZ );
		}
		//partial row/col block
		if( rlen%brlen!=0 && clen%bclen!=0 ) {
			estNNZ = (long) ((rlen%brlen)*(clen%bclen)*sparsity);
			blocks[3] = new MatrixBlock( (int)(rlen%brlen), (int)(clen%bclen), sparse, (int)estNNZ );
		}
		
		//space allocation
		for( MatrixBlock b : blocks )
			if( b != null )
				if( !sparse )
					b.allocateDenseBlockUnsafe(b.getNumRows(), b.getNumColumns());
		//NOTE: no preallocation for sparse (preallocate sparserows with estnnz) in order to reduce memory footprint
		
		return blocks;
	}

	public static MatrixBlock getMatrixBlockForReuse( MatrixBlock[] blocks, int rows, int cols, int brlen, int bclen ) {
		int index = -1;
		if( rows==brlen && cols==bclen )
			index = 0;
		else if( rows==brlen && cols<bclen )
			index = 1;
		else if( rows<brlen && cols==bclen )
			index = 2;
		else //if( rows<brlen && cols<bclen )
			index = 3;
		return blocks[ index ];
	}
}