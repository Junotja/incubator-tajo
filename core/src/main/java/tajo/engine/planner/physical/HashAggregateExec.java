/**
 * 
 */
package tajo.engine.planner.physical;

import com.google.common.annotations.VisibleForTesting;
import tajo.SubqueryContext;
import tajo.catalog.Schema;
import tajo.engine.exec.eval.EvalContext;
import tajo.engine.planner.logical.GroupbyNode;
import tajo.storage.Tuple;
import tajo.storage.VTuple;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

/**
 * This is the hash-based GroupBy Operator.
 * 
 * @author Hyunsik Choi
 */
public class HashAggregateExec extends AggregationExec {
  private Tuple tuple = null;
  private Map<Tuple, EvalContext[]> tupleSlots;
  private boolean computed = false;
  private Iterator<Entry<Tuple, EvalContext []>> iterator = null;

  /**
   * @throws IOException 
	 * 
	 */
  public HashAggregateExec(SubqueryContext ctx, GroupbyNode annotation,
                           PhysicalExec subOp) throws IOException {
    super(ctx, annotation, subOp);
    tupleSlots = new HashMap<Tuple, EvalContext[]>(10000);
    this.tuple = new VTuple(outputSchema.getColumnNum());
  }

  @VisibleForTesting
  public PhysicalExec getSubOp() {
    return this.child;
  }

  @VisibleForTesting
  public GroupbyNode getAnnotation() {
    return this.annotation;
  }

  @VisibleForTesting
  public void setSubOp(PhysicalExec subOp) {
    this.child = subOp;
  }
  
  private void compute() throws IOException {
    Tuple tuple;
    Tuple keyTuple;
    while((tuple = child.next()) != null && !ctx.isStopped()) {
      keyTuple = new VTuple(keylist.length);
      // build one key tuple
      for(int i = 0; i < keylist.length; i++) {
        keyTuple.put(i, tuple.get(keylist[i]));
      }
      
      if(tupleSlots.containsKey(keyTuple)) {
        EvalContext [] tmpTuple = tupleSlots.get(keyTuple);
        for(int i = 0; i < measureList.length; i++) {
          evals[measureList[i]].eval(tmpTuple[measureList[i]], inputSchema, tuple);
        }
      } else { // if the key occurs firstly
        EvalContext evalCtx [] = new EvalContext[outputSchema.getColumnNum()];
        for(int i = 0; i < outputSchema.getColumnNum(); i++) {
          evalCtx[i] = evals[i].newContext();
          evals[i].eval(evalCtx[i], inputSchema, tuple);
        }
        tupleSlots.put(keyTuple, evalCtx);
      }
    }
  }

  @Override
  public Tuple next() throws IOException {
    if(!computed) {
      compute();
      iterator = tupleSlots.entrySet().iterator();
      computed = true;
    }
        
    if(iterator.hasNext()) {
      EvalContext [] ctx =  iterator.next().getValue();
      for (int i = 0; i < ctx.length; i++) {
        tuple.put(i, evals[i].terminate(ctx[i]));
      }
      return tuple;
    } else {
      return null;
    }
  }

  @Override
  public Schema getSchema() {
    return outputSchema;
  }

  @Override
  public void rescan() throws IOException {    
    iterator = tupleSlots.entrySet().iterator();
    computed = true;
  }
}