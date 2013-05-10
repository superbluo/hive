/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.vector.expressions.aggregates.gen;

import java.util.ArrayList;

import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.vector.expressions.VectorExpression;
import org.apache.hadoop.hive.ql.exec.vector.expressions.aggregates.VectorAggregateExpression;
import org.apache.hadoop.hive.ql.exec.vector.expressions.aggregates
    .VectorAggregateExpression.AggregationBuffer;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.DoubleObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.LongObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorUtils;

/**
* VectorUDAFStdSampDouble. Vectorized implementation for VARIANCE aggregates. 
*/
@Description(name = "stddev_samp", value = "_FUNC_(x) - Returns the sample standard deviation of a set of numbers (vectorized, double)")
public class VectorUDAFStdSampDouble extends VectorAggregateExpression {
    
    /** 
    /* class for storing the current aggregate value. 
    */
    static private final class Aggregation implements AggregationBuffer {
      double sum;
      long count;
      double variance;
      boolean isNull;
      
      public void init () {
        isNull = false;
        sum = 0;
        count = 0;
        variance = 0;
      }
    }
    
    private VectorExpression inputExpression;
    private LongWritable resultCount;
    private DoubleWritable resultSum;
    private DoubleWritable resultVariance;
    private Object[] partialResult;
    
    private ObjectInspector soi;
    
    
    public VectorUDAFStdSampDouble(VectorExpression inputExpression) {
      super();
      this.inputExpression = inputExpression;
      partialResult = new Object[3];
      resultCount = new LongWritable();
      resultSum = new DoubleWritable();
      resultVariance = new DoubleWritable();
      partialResult[0] = resultCount;
      partialResult[1] = resultSum;
      partialResult[2] = resultVariance;
      initPartialResultInspector();
    }

  private void initPartialResultInspector () {
        ArrayList<ObjectInspector> foi = new ArrayList<ObjectInspector>();
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableDoubleObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableDoubleObjectInspector);

        ArrayList<String> fname = new ArrayList<String>();
        fname.add("count");
        fname.add("sum");
        fname.add("variance");

        soi = ObjectInspectorFactory.getStandardStructObjectInspector(fname, foi);
    }
    
    @Override
    public void aggregateInput(AggregationBuffer agg, VectorizedRowBatch unit) 
    throws HiveException {
      
      inputExpression.evaluate(unit);
      
      DoubleColumnVector inputVector = (DoubleColumnVector)unit.
        cols[this.inputExpression.getOutputColumn()];
      
      int batchSize = unit.size;
      
      if (batchSize == 0) {
        return;
      }
      
      Aggregation myagg = (Aggregation)agg;

      double[] vector = inputVector.vector;
      
      if (inputVector.isRepeating) {
        if (inputVector.noNulls || !inputVector.isNull[0]) {
          iterateRepeatingNoNulls(myagg, vector[0], batchSize);
        }
      } 
      else if (!unit.selectedInUse && inputVector.noNulls) {
        iterateNoSelectionNoNulls(myagg, vector, batchSize);
      }
      else if (!unit.selectedInUse) {
        iterateNoSelectionHasNulls(myagg, vector, batchSize, inputVector.isNull);
      }
      else if (inputVector.noNulls){
        iterateSelectionNoNulls(myagg, vector, batchSize, unit.selected);
      }
      else {
        iterateSelectionHasNulls(myagg, vector, batchSize, inputVector.isNull, unit.selected);
      }
    }

    private void  iterateRepeatingNoNulls(
        Aggregation myagg, 
        double value, 
        int batchSize) {
      
      if (myagg.isNull) {
        myagg.init ();
      }
      
      // TODO: conjure a formula w/o iterating
      //
      
      myagg.sum += value;
      myagg.count += 1;      
      if(myagg.count > 1) {
        double t = myagg.count*value - myagg.sum;
        myagg.variance += (t*t) / ((double)myagg.count*(myagg.count-1));
      }
      
      // We pulled out i=0 so we can remove the count > 1 check in the loop
      for (int i=1; i<batchSize; ++i) {
        myagg.sum += value;
        myagg.count += 1;      
        double t = myagg.count*value - myagg.sum;
        myagg.variance += (t*t) / ((double)myagg.count*(myagg.count-1));
      }
    }
  
    private void iterateSelectionHasNulls(
        Aggregation myagg, 
        double[] vector, 
        int batchSize,
        boolean[] isNull, 
        int[] selected) {
      
      for (int j=0; j< batchSize; ++j) {
        int i = selected[j];
        if (!isNull[i]) {
          double value = vector[i];
          if (myagg.isNull) {
            myagg.init ();
          }
          myagg.sum += value;
          myagg.count += 1;
        if(myagg.count > 1) {
          double t = myagg.count*value - myagg.sum;
          myagg.variance += (t*t) / ((double)myagg.count*(myagg.count-1));
        }
        }
      }
    }

    private void iterateSelectionNoNulls(
        Aggregation myagg, 
        double[] vector, 
        int batchSize, 
        int[] selected) {
      
      if (myagg.isNull) {
        myagg.init ();
      }

      double value = vector[selected[0]];
      myagg.sum += value;
      myagg.count += 1;
      if(myagg.count > 1) {
        double t = myagg.count*value - myagg.sum;
        myagg.variance += (t*t) / ((double)myagg.count*(myagg.count-1));
      }
      
      // i=0 was pulled out to remove the count > 1 check in the loop
      //
      for (int i=1; i< batchSize; ++i) {
        value = vector[selected[i]];
        myagg.sum += value;
        myagg.count += 1;
        double t = myagg.count*value - myagg.sum;
        myagg.variance += (t*t) / ((double)myagg.count*(myagg.count-1));
      }
    }

    private void iterateNoSelectionHasNulls(
        Aggregation myagg, 
        double[] vector, 
        int batchSize,
        boolean[] isNull) {
      
      for(int i=0;i<batchSize;++i) {
        if (!isNull[i]) {
          double value = vector[i];
          if (myagg.isNull) {
            myagg.init (); 
          }
          myagg.sum += value;
          myagg.count += 1;
        if(myagg.count > 1) {
          double t = myagg.count*value - myagg.sum;
          myagg.variance += (t*t) / ((double)myagg.count*(myagg.count-1));
        }
        }
      }
    }

    private void iterateNoSelectionNoNulls(
        Aggregation myagg, 
        double[] vector, 
        int batchSize) {
        
      if (myagg.isNull) {
        myagg.init ();
      }

      double value = vector[0];
      myagg.sum += value;
      myagg.count += 1;
      
      if(myagg.count > 1) {
        double t = myagg.count*value - myagg.sum;
        myagg.variance += (t*t) / ((double)myagg.count*(myagg.count-1));
      }
      
      // i=0 was pulled out to remove count > 1 check
      for (int i=1; i<batchSize; ++i) {
        value = vector[i];
        myagg.sum += value;
        myagg.count += 1;
        double t = myagg.count*value - myagg.sum;
        myagg.variance += (t*t) / ((double)myagg.count*(myagg.count-1));
      }
    }

    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      return new Aggregation();
    }

    @Override
    public void reset(AggregationBuffer agg) throws HiveException {
      Aggregation myAgg = (Aggregation) agg;
      myAgg.isNull = true;
    }

    @Override
    public Object evaluateOutput(
        AggregationBuffer agg) throws HiveException {
      Aggregation myagg = (Aggregation) agg;
      if (myagg.isNull) {
        return null;
      }
      else {
        assert(0 < myagg.count);
        resultCount.set (myagg.count);
        resultSum.set (myagg.sum);
        resultVariance.set (myagg.variance);
        return partialResult;
      }
    }
  @Override
    public ObjectInspector getOutputObjectInspector() {
      return soi;
    }
}
