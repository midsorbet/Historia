package edu.colorado.plv.bounder

import better.files.File
import edu.colorado.plv.bounder.Driver.{Default, RunMode}
import edu.colorado.plv.bounder.ir.JimpleFlowdroidWrapper
import edu.colorado.plv.bounder.lifestate.LifeState.{LSSpec, NI}
import edu.colorado.plv.bounder.lifestate.{FragmentGetActivityNullSpec, RxJavaSpec, SpecSignatures, SpecSpace}
import edu.colorado.plv.bounder.solver.ClassHierarchyConstraints
import edu.colorado.plv.bounder.symbolicexecutor.{CHACallGraph, FlowdroidCallGraph, SymbolicExecutor, SymbolicExecutorConfig, TransferFunctions}
import edu.colorado.plv.bounder.symbolicexecutor.state.{DBOutputMode, IPathNode, MemoryOutputMode$, OutputMode, PathNode, PrettyPrinting, Qry}
import scopt.OParser
import soot.SootMethod
import upickle.core.AbortException
import upickle.default.{macroRW, read, write, ReadWriter => RW}
import OptionPickler._

case class RunConfig(mode:RunMode = Default,
                     apkPath:String = "",
                     outFolder:Option[String] = None,
                     componentFilter:Option[Seq[String]] = None,
                     baseDir:Option[String] = None,
                     baseDirOut: Option[String] = None
                    ){
  val baseDirVar = "${baseDir}"
  val outDirVar = "${baseDirOut}"
  def getApkPath:String = baseDir match{
    case Some(baseDir) => {
      assert(apkPath.contains(baseDirVar), s"Apk path has no $baseDirVar to substitute.  APK path value: $apkPath")
      apkPath.replace(baseDirVar, baseDir)
    }
    case None => {
      assert(!apkPath.contains(baseDirVar))
      apkPath
    }
  }

  def getOutFolder:Option[String] = baseDirOut match{
    case Some(outDirBase) => {
      outFolder.map { outF =>
        assert(outF.contains(outDirVar), s"Out dir has no $outDirVar to substitute.  OutDir value: $outF")
        outF.replace(outDirVar, outDirBase)
      }
    }
    case None => {
      outFolder.map { outF =>
        assert(!outF.contains(baseDirVar))
        outF
      }
    }
  }
}

object RunConfig{
  implicit val rw:RW[RunConfig] = macroRW
}

object Driver {
  object RunMode{
    implicit val rw:RW[RunMode] = upickle.default.readwriter[String].bimap[RunMode](
      x => x.toString,
      {
        case v if Verify.toString == v => Verify
        case v if Info.toString == v => Info
        case v if Default.toString == v => Default
      }
    )

    def unapply(arg: String): Option[RunMode] = arg.toLowerCase() match{
      case "verify" => Some(Verify)
      case "info" => Some(Info)
      case "default" => Some(Default)
      case v => throw new IllegalArgumentException(s"Cannot parse $v as run mode")
    }
  }
  sealed trait RunMode
  case object Verify extends RunMode
  case object Info extends RunMode
  case object Default extends RunMode

  def main(args: Array[String]): Unit = {
    val builder = OParser.builder[RunConfig]
    val parser = {
      import builder._
      OParser.sequence(
        programName("Bounder"),
        opt[String]('m',"mode").optional().text("run mode [dottree, ...]").action{
          case ("verify",c) => c.copy(mode = Verify)
          case ("info",c) => c.copy(mode = Info)
          case (m,_) => throw new IllegalArgumentException(s"Unsupported mode $m")
        },
        opt[String]('a', "apkFile").optional().text("Compiled Android application").action{
          case (v,c) => c.copy(apkPath = v)
        },
        opt[String]('o', "outFolder").optional().text("folder to output analysis artifacts").action{
          case (v,c) => c.copy(outFolder = Some(v))
        },
        opt[Seq[String]]('f', "filter").optional()
          .valueName("com\\.example\\.foo\\.*,com\\.exaple\\.bar.* ...")
          .action((x, c) => c.copy(componentFilter = Some(x)))
          .text("Regex matching packages to analyze.  Note: use single back slash for regex escape on CLI."),
        opt[java.io.File]('c', "config").optional()
          .text("Json config file, use full option names as config keys.").action{(v,c) => {
            try {
              val readConfig = read[RunConfig](v)
              readConfig.copy(baseDir = c.baseDir, baseDirOut = c.baseDirOut)
            }catch{
              case t:AbortException =>
                System.err.println(s"parseing json exception: ${t.clue}")
                System.err.println(s"line: ${t.line}")
                System.err.println(s"index: ${t.index}")
                System.err.println(s"col: ${t.col}")
                t.printStackTrace()
                throw t
            }
          }
        },
        opt[String]('b', "baseDir").optional().text("Substitute for ${baseDir} in config file")
          .action((v,c) => c.copy(baseDir = Some(v))),
        opt[String]('u', "baseDirOut").optional().text("Substitute for ${baseDirOut} in config file")
          .action((v,c) => c.copy(baseDirOut = Some(v)))
      )
    }
    OParser.parse(parser, args, RunConfig()) match {
      case Some(cfg@RunConfig(Verify, _, _, componentFilter, _, _)) =>
        val cfgw = write(cfg)
        val apkPath = cfg.getApkPath
        val outFolder = cfg.getOutFolder

        val pathMode:OutputMode = outFolder match{
          case Some(outF) =>{
            val outFile = (File(outF) / "paths.db")
            if(outFile.exists) {
              implicit val opt = File.CopyOptions(overwrite = true)
              outFile.moveTo(File(outF) / "paths.db1")
            }
            DBOutputMode(outFile.canonicalPath)
          }
          case None => MemoryOutputMode$
        }
        val res = runAnalysis(apkPath, componentFilter,pathMode)
        val interpretedRes = BounderUtil.interpretResult(res)
        println(interpretedRes)
        outFolder.foreach { outF =>
          val outName = apkPath.split("/").last
          //TODO: pretty printing too slow
//          (new PrettyPrinting(pathMode)).dumpDebugInfo(res, outName, Some(outF))
          val resFile = File(outF) / "result.txt"
          resFile.overwrite(interpretedRes.toString)
        }
      case v => throw new IllegalArgumentException(s"Argument parsing failed: $v")
    }
  }

  def runAnalysis(apkPath: String, componentFilter:Option[Seq[String]], mode:OutputMode): Set[IPathNode] = {
    val startTime = System.currentTimeMillis()
    try {
      //TODO: read location from json config
      //TODO: read spec from json config
      val callGraph = CHACallGraph
//      val callGraph = FlowdroidCallGraph // flowdroid call graph immediately fails with "unreachable"
      val w = new JimpleFlowdroidWrapper(apkPath, callGraph)
      //TODO: un-hard code spec
      val specSet = Set(FragmentGetActivityNullSpec.getActivityNull,
        FragmentGetActivityNullSpec.getActivityNonNull,
        RxJavaSpec.call,
        RxJavaSpec.subscribeDoesNotReturnNull,
        RxJavaSpec.subscribeIsUniqueAndNonNull
      )
      val transfer = (cha: ClassHierarchyConstraints) =>
        new TransferFunctions[SootMethod, soot.Unit](w, new SpecSpace(specSet), cha)
      val config = SymbolicExecutorConfig(
        stepLimit = Some(300), w, transfer, component = componentFilter, outputMode = mode)
      val symbolicExecutor = config.getSymbolicExecutor
      val query = Qry.makeCallinReturnNull(symbolicExecutor, w,
        "de.danoeh.antennapod.fragment.ExternalPlayerFragment",
        "void updateUi(de.danoeh.antennapod.core.util.playback.Playable)", 200,
        callinMatches = ".*getActivity.*".r)
      symbolicExecutor.executeBackward(query)
    } finally {
      println(s"analysis time: ${(System.currentTimeMillis() - startTime) / 1000} seconds")
    }
  }
}
