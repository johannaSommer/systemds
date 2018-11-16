/*
 * Copyright 2018 Graz University of Technology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tugraz.sysds.common;

public class Types {

	/**
	 * Data types (tensor, matrix, scalar, frame, object, unknown).
	 */
	public enum DataType {
		TENSOR, MATRIX, SCALAR, FRAME, LIST, UNKNOWN;
		
		public boolean isMatrix() {
			return this == MATRIX;
		}
		public boolean isFrame() {
			return this == FRAME;
		}
		public boolean isScalar() {
			return this == SCALAR;
		}
		public boolean isList() {
			return this == LIST;
		}
	}

	/**
	 * Value types (int, double, string, boolean, unknown).
	 */
	public enum ValueType {
		FP32, FP64, INT, STRING, BOOLEAN, UNKNOWN;
		public boolean isNumeric() {
			return this == INT || this == FP32 || this == FP64;
		}
		public boolean isPseudoNumeric() {
			return isNumeric() || this == BOOLEAN;
		}
	}
}