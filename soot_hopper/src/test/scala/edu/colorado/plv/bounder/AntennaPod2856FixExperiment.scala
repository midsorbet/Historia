package edu.colorado.plv.bounder

import edu.colorado.plv.bounder.ir.JimpleFlowdroidWrapper
import edu.colorado.plv.bounder.lifestate.{FragmentGetActivityNullSpec, RxJavaSpec, SpecSpace}
import edu.colorado.plv.bounder.symbolicexecutor.state.{PrettyPrinting, Qry}
import edu.colorado.plv.bounder.symbolicexecutor.{CHACallGraph, ControlFlowResolver, DefaultAppCodeResolver, FlowdroidCallGraph, PatchedFlowdroidCallGraph, SparkCallGraph, SymbolicExecutor, SymbolicExecutorConfig, TransferFunctions}
import org.scalatest.funsuite.AnyFunSuite
import soot.SootMethod

class AntennaPod2856FixExperiment  extends AnyFunSuite{
  test("Prove location in stack trace is unreachable under a simple spec.") {
    val apk = getClass.getResource("/Antennapod-fix-2856-app-free-debug.apk").getPath
    assert(apk != null)
    val w = new JimpleFlowdroidWrapper(apk,CHACallGraph)
    val a = new DefaultAppCodeResolver[SootMethod, soot.Unit](w)
//    val resolver = new ControlFlowResolver[SootMethod, soot.Unit](w, a)
    val transfer = new TransferFunctions[SootMethod,soot.Unit](w,
      new SpecSpace(Set(FragmentGetActivityNullSpec.getActivityNull, RxJavaSpec.call)))
    val config = SymbolicExecutorConfig(
      stepLimit = Some(100), w,transfer, printProgress = true,
      component = Some(List("de\\.danoeh\\.antennapod\\.fragment\\.ExternalPlayerFragment.*")))
    val symbolicExecutor = config.getSymbolicExecutor
    val query = Qry.makeCallinReturnNonNull(symbolicExecutor, w,
      "de.danoeh.antennapod.fragment.ExternalPlayerFragment",
      "void updateUi(de.danoeh.antennapod.core.util.playback.Playable)",200,
      callinMatches = ".*getActivity.*".r)
    val result = symbolicExecutor.executeBackward(query)
    PrettyPrinting.printWitnessOrProof(result, "/Users/shawnmeier/Desktop/antennapod_fix_2856.dot")
    PrettyPrinting.printWitnessTraces(result, outFile="/Users/shawnmeier/Desktop/antennapod_fix_2856.witnesses")
  }

}