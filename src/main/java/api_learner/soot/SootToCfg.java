/**
 * 
 */
package api_learner.soot;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import soot.Body;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.Value;
import soot.jimple.DynamicInvokeExpr;
import soot.jimple.InterfaceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.VirtualInvokeExpr;
import soot.jimple.toolkits.ide.icfg.JimpleBasedInterproceduralCFG;
import soot.toolkits.exceptions.UnitThrowAnalysis;
import soot.toolkits.graph.ExceptionalUnitGraph;
import api_learner.Options;
import api_learner.soot.SootRunner.CallgraphAlgorithm;

/**
 * This is the main class for the translation. It first invokes Soot to load all
 * classes and perform points-to analysis and then translates them into
 * Boogie/Horn.
 * 
 * @author schaef
 *
 */
public class SootToCfg {

	private JimpleBasedInterproceduralCFG icfg = null;

	/**
	 * Run Soot and translate classes into Boogie/Horn
	 * 
	 * @param input
	 */
	public void run(String input) {

		// run soot to load all classes.
		SootRunner runner = new SootRunner();
		runner.run(input);

		if (Options.v().getCallGraphAlgorithm() != CallgraphAlgorithm.None) {
			System.err.println("Call Graph found");
			icfg = new JimpleBasedInterproceduralCFG();
		} else {
			System.err.println("No Callgraph. Improvising");
		}
		
		for (SootClass sc : Scene.v().getClasses()) {
			processSootClass(sc);
		}

	}

	/**
	 * Analyze a single SootClass and transform all its Methods
	 * 
	 * @param sc
	 */
	private void processSootClass(SootClass sc) {
		if (sc.resolvingLevel() < SootClass.SIGNATURES) {
			return;
		}

		if (sc.isApplicationClass()) {
			for (SootMethod sm : sc.getMethods()) {
				processSootMethod(sm);
			}
		}

	}

	private void processSootMethod(SootMethod sm) {
		if (sm.isConcrete()) {
			if (this.icfg != null) {
				transformStmtListICfg(sm.retrieveActiveBody());
			} else {
				transformStmtList(sm.retrieveActiveBody());
			}
		}
	}

	private void transformStmtListICfg(Body body) {
		SootMethod from = body.getMethod();
		for (Unit u : this.icfg.getCallsFromWithin(from)) {			
			for (SootMethod to : this.icfg.getCalleesOfCallAt(u)) {				
				MyCallGraph.v().addEdge(from, to);
			}
		}
	}

	/**
	 * Transforms a list of statements
	 * 
	 * @param body
	 *            Body
	 */
	private void transformStmtList(Body body) {
		ExceptionalUnitGraph tug = new ExceptionalUnitGraph(body,
				UnitThrowAnalysis.v());
		Iterator<Unit> stmtIt = tug.iterator();
		SootMethod m = body.getMethod();

		while (stmtIt.hasNext()) {
			Stmt s = (Stmt) stmtIt.next();
			if (s.containsInvokeExpr()) {
				InvokeExpr invoke = s.getInvokeExpr();
				if (invoke instanceof DynamicInvokeExpr) {
					DynamicInvokeExpr ivk = (DynamicInvokeExpr) invoke;
					// TODO: Log.error("no idea how to handle DynamicInvoke: " +
					// ivk);
					MyCallGraph.v().addEdge(m, ivk.getMethod());
				} else if (invoke instanceof InterfaceInvokeExpr) {
					InterfaceInvokeExpr ivk = (InterfaceInvokeExpr) invoke;
					for (SootMethod callee : foo(s, ivk.getBase(),
							ivk.getMethod())) {
						MyCallGraph.v().addEdge(m, callee);
					}
				} else if (invoke instanceof SpecialInvokeExpr) {
					SpecialInvokeExpr ivk = (SpecialInvokeExpr) invoke;
					// TODO: Log.info("not sure how to treat constructors");
					MyCallGraph.v().addEdge(m, ivk.getMethod());
				} else if (invoke instanceof StaticInvokeExpr) {
					StaticInvokeExpr ivk = (StaticInvokeExpr) invoke;
					MyCallGraph.v().addEdge(m, ivk.getMethod());
				} else if (invoke instanceof VirtualInvokeExpr) {
					VirtualInvokeExpr ivk = (VirtualInvokeExpr) invoke;
					for (SootMethod callee : foo(s, ivk.getBase(),
							ivk.getMethod())) {
						MyCallGraph.v().addEdge(m, callee);
					}
				}
			}
		}
	}

	private Set<SootMethod> foo(Stmt s, Value base, SootMethod callee) {
		Set<SootMethod> res = new HashSet<SootMethod>();
		SootClass sc = callee.getDeclaringClass();

		if (!sc.isJavaLibraryClass()) {
			// don't care about non application API calls.
			res.add(callee);
			return res;
		}

		if (callee.hasActiveBody()) {
			res.add(callee);
		}

		Collection<SootClass> possibleClasses;
		if (sc.isInterface()) {
			possibleClasses = Scene.v().getFastHierarchy()
					.getAllImplementersOfInterface(sc);
		} else {
			possibleClasses = Scene.v().getFastHierarchy().getSubclassesOf(sc);
		}
		for (SootClass sub : possibleClasses) {
			if (sub.resolvingLevel() < SootClass.SIGNATURES) {
				// Log.error("Not checking subtypes of " + sub.getName());
				// Then we probably really don't care.
			} else {
				if (sub.declaresMethod(callee.getSubSignature())) {
					res.add(sub.getMethod(callee.getSubSignature()));
				}
			}
		}

		if (res.isEmpty()) {
			res.add(callee);
		}
		return res;
	}

}
