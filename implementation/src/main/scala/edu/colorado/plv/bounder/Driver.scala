package edu.colorado.plv.bounder

import better.files.Dsl.mkdir
import edu.colorado.plv.bounder.ir.Messages

import java.io.{PrintWriter, StringWriter}
import java.sql.Timestamp
import java.time.Instant
import java.util.Date
import better.files.File
import edu.colorado.plv.bounder.BounderUtil.{DepthResult, Interrupted, MaxPathCharacterization, Proven, ResultSummary, Timeout, UnknownCharacterization, Unreachable, Witnessed, characterizeMaxPath}
import edu.colorado.plv.bounder.Driver.{Default, LocResult, RunMode, modeToString}
import edu.colorado.plv.bounder.ir.{AppLoc, CallbackMethodInvoke, CallbackMethodReturn, CallinMethodInvoke, CallinMethodReturn, GroupedCallinMethodInvoke, GroupedCallinMethodReturn, InternalMethodInvoke, InternalMethodReturn, JimpleMethodLoc, Loc, MethodLoc, SerializedIRLineLoc, SerializedIRMethodLoc, SkippedInternalMethodInvoke, SkippedInternalMethodReturn, SootWrapper, WitnessExplanation}
import edu.colorado.plv.bounder.lifestate.LifeState.{LSConstraint, LSSpec, LSTrue, OAbsMsg, Signature}
import edu.colorado.plv.bounder.lifestate.{FragmentGetActivityNullSpec, LifeState, LifecycleSpec, RxJavaSpec, SpecSpace}
import edu.colorado.plv.bounder.solver.{ClassHierarchyConstraints, Z3StateSolver}
import edu.colorado.plv.bounder.symbolicexecutor.state._
import edu.colorado.plv.bounder.symbolicexecutor.{AbstractInterpreter, ApproxMode, ExecutorConfig, LimitMaterializationApproxMode, QueryFinished, SparkCallGraph, TransferFunctions, Z3TimeoutBehavior}
import edu.colorado.plv.bounder.synthesis.{EnumModelGenerator, LearnFailure, LearnSuccess}
import org.slf4j.LoggerFactory
import org.slf4j.impl.Log4jLoggerAdapter
import scopt.OParser

import scala.concurrent.Await
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.SQLActionBuilder
import soot.SootMethod
import ujson.{Arr, Bool, Null, Num, Obj, Str, Value, validate}
import upickle.core.AbortException
import upickle.default.{macroRW, read, write, ReadWriter => RW}

import java.io
import scala.collection.immutable.{AbstractSet, SortedSet}
import scala.concurrent.duration._
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Properties
import scala.util.matching.Regex

//TODO: dboutput mode is failing in truncate mode.  Cannot run non truncate for perf reasons.
case class Action(mode:RunMode = Default,
                  baseDirOut: Option[String] = None,
                  baseDirApk:Option[String] = None,
                  config: RunConfig = RunConfig(),
                  filter:Option[String] = None, // for making allderef queries - only process classes beginning with
                  tag:Option[String] = None,
                  outputMode: String = "NONE", // "DB" or "MEM" for writing nodes to file or keeping in memory.
                  dbg:Boolean = false
                 ){
  def runCmdFork(jarPath:String): Option[Throwable] = {
    try {
      File.usingTemporaryFile() { cfgTmp =>
        cfgTmp.overwrite(write(config))
        val cmd = s"-m ${modeToString(mode)} -b ${baseDirApk.get} -u ${baseDirOut.get} -c ${cfgTmp.pathAsString} " +
          s"-o outputMode"
        val cmd2 = filter.map(f => cmd + s" -f ${f}").getOrElse(cmd)
        val cmd3 = tag.map(f => cmd2 + s" -t ${f}").getOrElse(cmd)
        BounderUtil.runCmdStdout(s"java -jar ${jarPath} $cmd3")
      }
      None
    }catch {
      case t : Throwable =>
        println(s"failed: ${filter}")
        Some(t)
    }
  }

  val baseDirVar = "${baseDir}"
  val outDirVar = "${baseDirOut}"
  def getApkPath:String = baseDirApk match{
    case Some(baseDir) => {
//      assert(config.apkPath.contains(baseDirVar),
//        s"Apk path has no $baseDirVar to substitute.  APK path value: ${config.apkPath}")
      config.apkPath.replace(baseDirVar, baseDir)
    }
    case None => {
      config.apkPath
    }
  }
  def getOutFolder:String = baseDirOut match{
    case Some(outDirBase) => {
      config.outFolder.map { outF =>
//        assert(outF.contains(outDirVar), s"Out dir has no $outDirVar to substitute.  OutDir value: $outF")
        outF.replace(outDirVar, outDirBase)
      }.get
    }
    case None => {
      config.outFolder.map { outF =>
        assert(!outF.contains(baseDirVar))
        outF
      }
    }.get
  }
}

/**
 *
 * @param heuristicType Tag of the heuristic that generated a given config
 * @param specRefinement Human made label distinguishing different experiment runs
 * @param other comma separated list of other params (TODO: break these out into fields if we rerun experiments)
 *              0: Output mode
 */
case class ExpTag(heuristicType:String = "", specRefinement:String = "", other:String = "")
object ExpTag{
  implicit val rw:RW[ExpTag] = macroRW
}
case class RunConfig( //Mode can also be specified in run config
                     apkPath:String = "",
                     outFolder:Option[String] = None,
                     componentFilter:Option[Seq[String]] = None,
                     specSet: SpecSetOption = TopSpecSet,
                     initialQuery: List[InitialQuery] = Nil,
                     limit:Int = -1, // step limit
                     samples:Int = 5,
                     tag:ExpTag = ExpTag(),
                     timeLimit:Int = 600, // max clock time per query
                     truncateOut:Boolean = true,
                      configPath:Option[String] = None, //Note: overwritten with json path of config
                    approxMode: ApproxMode = LimitMaterializationApproxMode(),
                      z3TimeoutBehavior: Option[Z3TimeoutBehavior] = None
                    ){
}

object RunConfig{
  implicit val rw:RW[RunConfig] = macroRW
}

object Driver {
  object RunMode {
    implicit val rw: RW[RunMode] = upickle.default.readwriter[String].bimap[RunMode](
      x => x.toString,
      {
        case v if Verify.toString == v => Verify
        case v if Info.toString == v => Info
        case v if Default.toString == v => Default
      }
    )

  }

  sealed trait RunMode
  case object SolverServer extends RunMode

  case object Verify extends RunMode
  case object Synthesize extends RunMode

  case object Info extends RunMode

  case object Default extends RunMode

  case object SampleDeref extends RunMode

  case object ReadDB extends RunMode

  case object ExpLoop extends RunMode

  case object MakeAllDeref extends RunMode

  case object MakeSensitiveDerefFieldCausedFinish extends RunMode
  case object MakeSensitiveDerefFieldCausedSync extends RunMode

  case object MakeSensitiveDerefCallinCaused extends RunMode
  case object ExportPossibleMessages extends RunMode

  /**
   * Find locations of all callins used in disallow specs from config file.
   * TODO: if null returned from callin, find places where its dereferenced
   */
  case object FindCallins extends RunMode

  def readDB(outFolder: File, findNoPred: Boolean = false): Unit = {
    val dbPath = outFolder / "paths.db"
    implicit val db = DBOutputMode(dbPath.toString())
    val liveNodes: Set[IPathNode] = db.getTerminal().map(v => v)
    PrettyPrinting.dumpDebugInfo(liveNodes, "out", outDir = Some(outFolder.toString))

    if (findNoPred) {
      val noPredNodes: Set[IPathNode] = db.getNoPred().map(v => v)
      PrettyPrinting.dumpDebugInfo(noPredNodes, "noPred", outDir = Some(outFolder.toString))
    }
  }

  def setZ3Path(path: String): Unit = {
    val newPath = Array(path) ++ System.getProperty("java.library.path").split(":")
    System.setProperty("java.library.path", newPath.distinct.mkString(":"))
    //set sys_paths to null so that java.library.path will be reevaluated next time it is needed
    val sysPathsField = classOf[ClassLoader].getDeclaredField("sys_paths");
    sysPathsField.setAccessible(true);
    sysPathsField.set(null, null);
    println(s"java.library.path set to: ${System.getProperty("java.library.path")}")
  }

  val stringToMode:Map[String,RunMode] = Map(
    "solverServer" -> SolverServer,
    "verify" -> Verify,
    "synthesize" -> Synthesize,
    "info" -> Info,
    "sampleDeref" -> SampleDeref,
    "readDB" -> ReadDB,
    "expLoop" -> ExpLoop,
    "makeAllDeref" -> MakeAllDeref,
    "findCallinsPattern" -> FindCallins,
    "nullFieldPatternFinish" -> MakeSensitiveDerefFieldCausedFinish,
    "nullFieldPatternSync" -> MakeSensitiveDerefFieldCausedSync,
    "nullCallinPattern" -> MakeSensitiveDerefCallinCaused,
    "exportPossibleMessages" -> ExportPossibleMessages
  )
  lazy val modeToString: Map[RunMode,String] = stringToMode.map{case (k,v) => v->k}
  def decodeMode(modeStr: Any): RunMode = modeStr match {
    case Str(value) => decodeMode(value)
    case s:String if stringToMode.contains(s) => stringToMode(s)
    case m =>
      throw new IllegalArgumentException(s"Unsupported mode $m. Options: ${stringToMode.keySet}")
  }
  def encodeMode(mode:RunMode):String = modeToString(mode)

  def main(args: Array[String]): Unit = {
    val builder = OParser.builder[Action]
    val parser = {
      import builder._
      OParser.sequence(
        programName("Bounder"),
        opt[String]('m', "mode").optional().text("run mode [verify, info, sampleDeref]").action { (v, c) =>
          c.copy(mode = decodeMode(v))
        },
        opt[String]('b', "baseDirApk").optional().text("Substitute for ${baseDir} in config file")
          .action((v, c) => c.copy(baseDirApk = Some(v))),
        opt[String]('u', "baseDirOut").optional().text("Substitute for ${baseDirOut} in config file")
          .action((v, c) => c.copy(baseDirOut = Some(v))),
        opt[java.io.File]('c', "config").optional()
          .text("Json config file, use full option names as config keys.").action { (v, c) => {
          try {
            val configurationPath = v.getAbsolutePath
            val readConfig = read[RunConfig](v).copy(configPath = Some(configurationPath))

            // Extract mode option if set
            val cfgFile = File(configurationPath)
            if (cfgFile.notExists)
              throw new IllegalArgumentException(s"file does not exist: ${configurationPath}")
            val vStr = cfgFile.contentAsString
            val c1 = c.copy(config = readConfig)
            ujson.read(vStr) match {
              case Obj(value) if value.contains("mode") => c1.copy(mode = decodeMode(value("mode")))
              case Obj(_) => c1
              case v =>
                throw new IllegalArgumentException(s"Invalid config json, top level must be object, found: $v")
            }
          } catch {
            case t: AbortException =>
              System.err.println(s"parseing json exception: ${t.clue}")
              System.err.println(s"line: ${t.line}")
              System.err.println(s"index: ${t.index}")
              System.err.println(s"col: ${t.col}")
              t.printStackTrace()
              throw t
          }
        }
        },
        opt[String]('f', "filter").optional()
          .text("Package filter for sampling(currently only supported by makeAllDeref)")
          .action((v, c) => c.copy(filter = Some(v))),
        opt[String]('t', "tag").optional()
          .text("Tag for experiment, recorded when running")
          .action((v, c) => c.copy(tag = Some(v))),
        opt[String]('o', "outputMode").optional()
          .text("keep intermediate path in mem (MEM), write to db (DB), or discard (NONE)")
          .action((v, c) => c.copy(outputMode = v)),
        opt[Unit]("debug").optional()
          .text("Override timeout and truncate output in config.")
          .action((_,c) => c.copy(dbg = true)),
        opt[String]("z3path").optional()
          .text("Force set z3 path for stupid macos dyld problem")
          .action((path,action) => {
            setZ3Path(path)
            action})
      )
    }
    OParser.parse(parser, args, Action()) match {
      case Some(act) if act.baseDirApk.isDefined && act.baseDirOut.isDefined =>
        runAction(act)
      case Some(act) if act.mode == ExpLoop =>
        runAction(act) // don't worry about paths if exp loop
      case Some(act) =>
        // If base directories are not defined, assume same as config
        assert(act.config.configPath.isDefined, "Internal failure, config file path not defined")
        val baseDir = act.config.configPath.map(p => File(p).path.getParent.toString)
        val actWithBase = act.copy(baseDirApk = baseDir, baseDirOut = baseDir)
        runAction(actWithBase)
      case None => throw new IllegalArgumentException("Argument parsing failed")
    }
  }

  def expLoop(): Unit = {
    val expDb = new ExperimentsDb
    expDb.loop()
    println()
  }

  // [qry,id,loc, res, time]
  case class LocResult(q: InitialQuery, sqliteId: Int, loc: Loc, resultSummary: ResultSummary,
                       maxPathCharacterization: MaxPathCharacterization, time: Long,
                       depthChar: BounderUtil.DepthResult, witnesses: List[List[String]],
                       witnessExplanation: List[WitnessExplanation])

  object LocResult {
    implicit var rw: RW[LocResult] = macroRW
  }

  def loopSolver():Unit = {
    //TODO: implement stdio for solver part to avoid segfault and oom
//    val solver = new Z3StateSolver()
//    while(true){

//    }
  }
  def runAction(act: Action): Unit = {
    println(s"java.library.path set to: ${System.getProperty("java.library.path")}")
    act match {
      case Action(SolverServer, _,_,_,_,_, _, _) =>
          loopSolver()
      case act@Action(Synthesize, _,_,cfgIn, filter, _, mode, dbg) =>
        verifSynthAction(act, cfgIn, filter, mode, dbg)
      case act@Action(Verify, _, _, cfgIn, filter, _, mode,dbg) =>
        verifSynthAction(act, cfgIn, filter, mode, dbg)
      case act@Action(SampleDeref, _, _, cfg, _, _, _, _ ) =>
        sampleDeref(cfg, act.getApkPath, act.getOutFolder, act.filter)
      case act@Action(FindCallins, _, _, cfg, _, _, _, _) =>
        findCallins(cfg, act.getApkPath, act.getOutFolder, act.filter)
      case act@Action(MakeSensitiveDerefCallinCaused, _, _, cfg, _, _, _, _) =>
        makeSensitiveDerefCallinCaused(cfg, act.getApkPath, act.getOutFolder, act.filter)
      case act@Action(MakeSensitiveDerefFieldCausedFinish, _, _, cfg, _, _, _, _) =>
        makeSensitiveDerefFieldCaused(cfg, act.getApkPath, act.getOutFolder, act.filter, "Finish")
      case act@Action(MakeSensitiveDerefFieldCausedSync, _, _, cfg, _, _, _, _) =>
        makeSensitiveDerefFieldCaused(cfg, act.getApkPath, act.getOutFolder, act.filter, "Synch")
      case act@Action(ReadDB, _, _, _, _, _, _, _) =>
        readDB(File(act.getOutFolder))
      case Action(ExpLoop, _, _, _, _, _, _, _) =>
        expLoop()
      case act@Action(MakeAllDeref, _, _, cfg, _, tag, _, _) =>
        makeAllDeref(act.getApkPath, act.filter, File(act.getOutFolder), cfg, ExpTag(other = tag.getOrElse("")))
      case Action(Info, Some(out), Some(apk), cfg, _, _, _, _) =>
        info(cfg, out, apk)
      case act@Action(ExportPossibleMessages, _,_,cfg, _,_,_, _) =>
        outputMessages(act.getOutFolder, act.getApkPath)
      case v => throw new IllegalArgumentException(s"Invalid action: $v")
    }
  }

  private def verifSynthAction(act: Action, cfgIn: RunConfig, filter: Option[String], mode: String, dbg: Boolean): Unit = {
    val cfgWithTime = if (dbg) {
      cfgIn.copy(timeLimit = 14400 * 2, truncateOut = false)
    } else cfgIn
    val cfg = if (filter.isDefined) cfgWithTime.copy(componentFilter = Some(filter.get.split(':'))) else cfgWithTime
    val apkPath = act.getApkPath
    val outFolder: String = act.getOutFolder
    // Create output directory if not exists
    // TODO: move db creation code to better location
    File(outFolder).createIfNotExists(asDirectory = true)
    val initialQuery = cfg.initialQuery
    if (initialQuery.isEmpty)
      throw new IllegalArgumentException("Initial query must be defined for verify")
    val stepLimit = cfg.limit
    val outFile = (File(outFolder) / "paths.db")
    if (outFile.exists) {
      implicit val opt = File.CopyOptions(overwrite = true)
      outFile.moveTo(File(outFolder) / "paths.db1")
    }
    val pathMode = if (mode == "DB") {
      DBOutputMode(outFile.canonicalPath)
    } else if (mode == "MEM") {
      MemoryOutputMode
    } else if (mode == "NONE") {
      NoOutputMode
    } else throw new IllegalArgumentException(s"Mode ${mode} is invalid, options: DB - write nodes to sqlite, MEM " +
      s"- keep nodes in memory.")
    act.mode match {
      case Verify => {
        val res: List[LocResult] =
          runAnalysis(cfg, apkPath, pathMode, stepLimit, initialQuery, Some(outFolder), dbg)
        res.zipWithIndex.foreach { case (iq, ind) =>
          val resFile = File(outFolder) / s"result_${ind}.txt"
          resFile.overwrite(write(iq))
        }
      }
      case Synthesize => {
        val specSet = cfg.specSet
        val specSpace = specSet.getSpecSpace()

        val w = new SootWrapper(apkPath,
          specSet.getSpecSet().flatMap{s => s.pred.allMsg + s.target}
            .union(specSet.getDisallowSpecSet().flatMap{s => s.pred.allMsg + s.target})
            .union(specSpace.getMatcherSpace))
        assert(initialQuery.size == 1, "must have exactly one initial query for synthesis")
        val initialQueryS = initialQuery.head
        val reachable:Set[InitialQuery] = Set() //TODO: get this out of config somehow
        val config = ExecutorConfig(
          stepLimit = stepLimit, w, specSet.getSpecSpace(), component = cfg.componentFilter, outputMode = pathMode,
          timeLimit = cfg.timeLimit, printAAProgress = dbg)
        val gen = new EnumModelGenerator(
          initialQueryS,
          reachable,
          specSpace,
          config //TODO: may want to add pkg filters if things get slow
        )
        val res = gen.run()
        val resTxt = res match {
          case LearnSuccess(space) =>
            val builder = new StringBuilder()
            builder.append("final specification Row 5")
            builder.append("-------------------")
            val spaceStr = space.toString
            builder.append(spaceStr)
            builder.append("dumping debug info")

            builder.append("\nstats for starting spec row 5")
            builder.append("---------------------")
            builder.append(specSpace.stats().map { r => s"${r._1} : ${r._2}\n" })
            builder.append("\nstats for final spec row 5")
            builder.append("---------------------")
            builder.append(space.stats().map { r => s"${r._1} : ${r._2}\n" })
            builder.append("\nruntime stats")
            builder.append("---------------------")
            builder.append(gen.getStats().map { r => s"${r._1} : ${r._2}\n" })
            val outf = File(outFolder) / s"result_synthesis_human.txt"
            val outString = builder.toString
            outf.overwrite(outString)
            val specOut = File(outFolder) / s"result_synthesis_spec.json"
            specOut.overwrite(write[PickleSpec](PickleSpec.mk(space)))
            outString
          case LearnFailure => "failed to learn a sufficient spec"
        }
        println(resTxt)
      }
      case mode => throw new IllegalArgumentException(s"method does not work for ${mode}")
    }

  }

  def detectProguard(apkPath: String): Boolean = {
    import sys.process._
    val cmd = (File(BounderSetupApplication.androidHome) / "tools" / "bin" / "apkanalyzer").toString
    var stdout: List[String] = List()
    val stderr = new StringBuilder

    val status = s"$cmd -h dex packages ${apkPath.replace(" ", "\\ ")}".!(ProcessLogger(v => {
      stdout = v :: stdout
    }, stderr append _))
    if (status != 0) {
      throw new IllegalArgumentException(s"apk: $apkPath  error: $stderr")
    }
    stdout.exists(v => v.contains("a.a.a."))
  }

  def outputMessages(out:String, apkPath:String):Unit = {
    //TODO: finish at some point
    val outFile = File(out)
    val w = new SootWrapper(apkPath, Set())
    val config = ExecutorConfig(
      stepLimit = 0, w, new SpecSpace(Set()))
    val interpreter = config.getAbstractInterpreter
    val resolver = interpreter.appCodeResolver
    val cfRes = interpreter.controlFlowResolver
    val allMethods: Set[MethodLoc] = resolver.appMethods.filter{m => ???}
    val callins:Set[Signature] = allMethods.flatMap{appMethod => cfRes.filterResolver
      .directCallsGraph(cfRes.getWrapper,appMethod).flatMap {
        case CallinMethodReturn(sig) => Some(sig)
        case CallinMethodInvoke(sig) => Some(sig)
        case GroupedCallinMethodInvoke(_, _) => throw new IllegalStateException("should not group here")
        case GroupedCallinMethodReturn(_, _) => throw new IllegalStateException("should not group here")
        case _ => None
    }}

    val callbacks: Set[Signature] = resolver.getCallbacks.map{cb => cb.getSignature}

    val callinF = outFile / "Callins_"
    callinF.overwrite(callins.map{sig => s"${sig.base} , ${sig.methodSignature}"}.mkString("\n"))

  }
  def info(cfg: RunConfig, outBase: String, apkBase: String): Unit = {
    val apk = cfg.apkPath.replace("${baseDir}", apkBase)
    val outFile = File(cfg.outFolder.get.replace("${baseDirOut}", outBase)) / "out.db" //File(baseDirOut) / "out.db"
    val w = new SootWrapper(apk, Set(), SparkCallGraph)

    val pathMode = DBOutputMode(outFile.canonicalPath)
    val config = ExecutorConfig(
      stepLimit = 2, w, new SpecSpace(Set()), component = None, outputMode = pathMode,
      timeLimit = cfg.timeLimit)
    val symbolicExecutor: AbstractInterpreter[SootMethod, soot.Unit] = config.getAbstractInterpreter
    val mFilter = if(cfg.componentFilter.isDefined){
      val filters = cfg.componentFilter.get.map{v => v.r}
      (cname:String) => filters.exists(_.matches(cname))
    }else{
      (_:String) => true
    }

    val messages: Messages = w.getMessages(symbolicExecutor.controlFlowResolver, cfg.specSet.getSpecSpace(),
      symbolicExecutor.getClassHierarchy, mFilter)
    (outFile.parent / "messages_component.json").overwrite(write(messages))
    val messages_noComponent = w.getMessages(symbolicExecutor.controlFlowResolver, cfg.specSet.getSpecSpace(),
      symbolicExecutor.getClassHierarchy, (_:String) => true)
    (outFile.parent / "messages_all.json").overwrite(write(messages_noComponent))

    symbolicExecutor.writeIR()
  }

  def makeAllDeref(apkPath: String, filter: Option[String],
                   outFolder: File, cfg: RunConfig, tag: ExpTag) = {
    val callGraph = SparkCallGraph
    val w = new SootWrapper(apkPath, Set(), callGraph)
    val config = ExecutorConfig(
      stepLimit = 0, w, new SpecSpace(Set()))
    val symbolicExecutor: AbstractInterpreter[SootMethod, soot.Unit] = config.getAbstractInterpreter
    val appClasses = symbolicExecutor.appCodeResolver.appMethods.map(m => m.classType)
    val filtered = appClasses.filter(c => filter.forall(c.startsWith))
    val initialQueries = filtered.map(c => AllReceiversNonNull(c))
    initialQueries.foreach { q =>
      //TODO: should we group more classes together in a job?
      val cfgOut = cfg.outFolder.get
      val cfg2 = cfg.copy(initialQuery = List(q),
        tag = tag, outFolder = Some(cfgOut + "/" + q.className))
      val fname = outFolder / s"${q.className}.json"
      if (fname.exists()) fname.delete()
      fname.append(write(cfg2))
    }
  }

  /**
   * Used to split sensitive callins by whether they can throw an exception or return null value
   * @param hasNullHead
   * @param locs
   * @return
   */
  private def splitNullHead(hasNullHead:Boolean, locs: Set[LSSpec]):Set[OAbsMsg] = {
    locs.flatMap{ spec =>
      def out(res:Boolean) = if(res == hasNullHead) Some(spec.target) else None
      val target = spec.target
      target.lsVars.headOption match {
        case Some(NullVal) => out(true)
        case Some(pv:PureVar) => if(spec.rhsConstraints.exists{
          case LSConstraint(pv2, Equals, NullVal) if pv2 == pv => true
          case _ => false
        }) out(true) else out(false)
        case Some(_) => out(false)
        case None => out(false)
      }
    }
  }
  private def writeInitialQuery(cfg:RunConfig, queries:Iterable[InitialQuery], qPrefix:String, outf:File):Unit = {
    queries.zipWithIndex.foreach{case (query, index) =>
      val f = outf / s"${qPrefix}_$index.json"
      println(s"writing initial query: ${f.toString}")
      assert(!f.exists, s"File exists:${f}")
      val cfgWithInit = cfg.copy(initialQuery = List(query))
      f.overwrite(write[RunConfig](cfgWithInit))
    }
  }
  def makeSensitiveDerefCallinCaused(cfg: RunConfig, apkPath: String, outFolder: String,
                                     filter: Option[String]): Unit = {
    val outf = File(outFolder)
    if (!outf.exists)
      mkdir(outf) // make dir if not exists
    val w = new SootWrapper(apkPath, Set())
    val config = ExecutorConfig(
      w = w, specSpace = new SpecSpace(Set()), component = None)
    val executor: AbstractInterpreter[SootMethod, soot.Unit] = config.getAbstractInterpreter
    val specSet = cfg.specSet.getSpecSpace()
    val toFind = splitNullHead(hasNullHead = true, specSet.getDisallowSpecs)
    val heuristicType = "SensitiveDerefCallinCaused"
    val outCfg = cfg.copy(tag = cfg.tag.copy(heuristicType = heuristicType))
    writeInitialQuery(outCfg,executor.appCodeResolver.heuristicCiFlowsToDeref(toFind, filter, executor),
      heuristicType, outf)
  }

  def makeSensitiveDerefFieldCaused(cfg: RunConfig, apkPath: String, outFolder: String,
                                    filter: Option[String], pattern:String): Unit ={
    val outf = File(outFolder)
    if (!outf.exists)
      mkdir(outf) // make dir if not exists
    val w = new SootWrapper(apkPath, Set())
    val config = ExecutorConfig(
      w = w, specSpace = new SpecSpace(Set()), component = None)
    val interpreter: AbstractInterpreter[SootMethod, soot.Unit] = config.getAbstractInterpreter
    //    val specSet = cfg.specSet.getSpecSpace()
    //    assert(specSet.getDisallowSpecs.isEmpty && specSet.getSpecs.isEmpty,
    //      "Sensitive field caused deref does not use specs")
    val derefFieldNulls = if(pattern == "Finish")
      interpreter.appCodeResolver.heuristicDerefNullFinish(filter, interpreter)
    else if (pattern == "Synch")
      interpreter.appCodeResolver.heuristicDerefNullSynch(filter, interpreter)
    else
      throw new IllegalArgumentException(s"Unsupported deref pattern: ${pattern}")
    val heuristicType = s"SensitiveDerefFieldCaused${pattern}"
    val outCfg = cfg.copy(tag = cfg.tag.copy(heuristicType = heuristicType))
    writeInitialQuery(outCfg,derefFieldNulls, heuristicType, outf)
  }


  /**
   * Find callin usages matching the targets of the disallow specifications in the config
   * @param cfg config with disallow specifications
   * @param apkPath path to the target apk
   * @param outFolder folder to write config files
   * @param filter app packages to include
   */
  def findCallins(cfg: RunConfig, apkPath:String, outFolder:String, filter:Option[String]): Unit = {
    val outf = File(outFolder)
    if(!outf.exists)
      mkdir(outf) // make dir if not exists
    val w = new SootWrapper(apkPath, Set())
    val config = ExecutorConfig(
      w = w, specSpace = new SpecSpace(Set()), component = None)
    val symbolicExecutor: AbstractInterpreter[SootMethod, soot.Unit] = config.getAbstractInterpreter
    val specSet = cfg.specSet.getSpecSpace()
    val toFind = splitNullHead(hasNullHead = false, specSet.getDisallowSpecs)
    val locations: Set[(AppLoc, OAbsMsg)] = symbolicExecutor.appCodeResolver.findCallinsAndCallbacks(toFind, filter)


    val disallowedCallins: Set[(DisallowedCallin, LSSpec)] = locations.flatMap {
      case (loc, msg) =>
        try {
          val spec = specSet.getDisallowSpecs.find { s => s.target == msg }.get
          Some(DisallowedCallin.mk(loc, spec), spec)
        } catch {
            case e: AssertionError if e.toString.contains("Disallow must be callin entry") => None
        }
    }


    disallowedCallins.zipWithIndex.foreach{case (initialQuery, index) =>
      val disallow_ident = initialQuery._2.target.identitySignature
      val heuristicType = s"Disallow.${disallow_ident}"
      val fName = s"${heuristicType}_${index}.json"
      val f = outf / fName
      assert(!f.exists, s"File $f exists")
      val qry = initialQuery._1
      val spec = PickleSpec.mk(new SpecSpace(Set.empty, Set(initialQuery._2)))
      val cCfg = cfg.copy(initialQuery = List(qry),specSet = spec, tag = cfg.tag.copy(heuristicType = heuristicType))
//      val fName = qry.fileName
      val contents = write(cCfg)
      f.write(contents)
    }
  }
  def sampleDeref(cfg: RunConfig, apkPath:String, outFolder:String, filter:Option[String]) = {
    val n = cfg.samples
    val callGraph = SparkCallGraph
    val w = new SootWrapper(apkPath, Set(), callGraph)
    val config = ExecutorConfig(
      stepLimit = n, w, new SpecSpace(Set()), component = cfg.componentFilter)
    val symbolicExecutor: AbstractInterpreter[SootMethod, soot.Unit] = config.getAbstractInterpreter

//    val queries = (0 until n).map{_ =>
//      val appLoc = symbolicExecutor.appCodeResolver.sampleDeref(filter)//
//      val name = appLoc.method.simpleName
//      val clazz = appLoc.method.classType
//      val line = appLoc.line.lineNumber
//      ReceiverNonNull(clazz, name, line)
//    }.toList
    val queries = scala.collection.mutable.Set[ReceiverNonNull]()
    val sizesPrinted = scala.collection.mutable.Set[Int]()
    while(queries.size < n){
      if(queries.size %10 == 0 && !sizesPrinted.contains(queries.size)) {
        println(s"sampels: ${queries.size}")
        sizesPrinted.add(queries.size)
      }
      val appLoc = symbolicExecutor.appCodeResolver.sampleDeref(filter)
      val sig = appLoc.method.getSignature
      val line = appLoc.line.lineNumber
      queries.add(ReceiverNonNull(sig, line,None))
    }
    val outName = s"sample"
    val f = File(outFolder) / s"$outName.json"
    val writeCFG = cfg.copy(initialQuery = queries.toList,
      outFolder = cfg.outFolder.map(v => v + "/" + outName))
    if(f.exists()) f.delete()
    f.createFile()
    f.write(write(writeCFG))
  }

  def runAnalysis(cfg:RunConfig, apkPath: String, mode:OutputMode, stepLimit:Int,
                  initialQueries: List[InitialQuery], outDir:Option[String], dbg:Boolean): List[LocResult] = {
    val specSet = cfg.specSet
    val startTime = System.nanoTime()
    try {
      val w = new SootWrapper(apkPath, specSet.getSpecSet().union(specSet.getDisallowSpecSet()))
      val config = ExecutorConfig(
        stepLimit = stepLimit, w, specSet.getSpecSpace(), component = cfg.componentFilter, outputMode = mode,
        timeLimit = cfg.timeLimit, printAAProgress = dbg, approxMode = cfg.approxMode,
        z3Timeout = cfg.z3TimeoutBehavior.getOrElse(Z3TimeoutBehavior()))
      initialQueries.flatMap{ initialQuery =>
        try {
          val symbolicExecutor: AbstractInterpreter[SootMethod, soot.Unit] = config.getAbstractInterpreter
          val results: Set[symbolicExecutor.QueryData] = symbolicExecutor.run(initialQuery, mode, cfg)

          val grouped: Seq[LocResult] = results.groupBy(v => (v.queryId, v.location)).map { case ((id, loc), groupedResults) =>
            val res = groupedResults.map(res => BounderUtil.interpretResult(res.terminals, res.result))
              .reduce(reduceResults)
            val characterizedMaxPath: MaxPathCharacterization =
              groupedResults.map(res => BounderUtil.characterizeMaxPath(res.terminals)(mode))
                .reduce(BounderUtil.reduceCharacterization)
            val finalTime = groupedResults.map(_.runTime).sum
            // get minimum cb count and instruction count from nodes live at end
            // also retrieve up to 3 witnesses
            val finalLiveNodes = groupedResults.flatMap { res =>
              res.terminals.filter { pathNode =>
                pathNode.qry.isLive && pathNode.subsumed(mode).isEmpty
              }
            }
            //val depth = if(finalLiveNodes.nonEmpty) Some(finalLiveNodes.map{n => n.depth}.min) else None
            //val ordDepth = if(finalLiveNodes.nonEmpty) Some(finalLiveNodes.map{_.ordDepth}.min) else None
            val pp = PrettyPrinting
            val live: List[List[String]] = pp.nodeToWitness(finalLiveNodes.toList, cfg.truncateOut)(mode).sortBy(_.length).take(2)
            val witnessed = pp.nodeToWitness(groupedResults.flatMap { res =>
              res.terminals.filter { pathNode =>
                pathNode.qry match {
                  case Qry(_, _, WitnessedQry(_)) => true
                  case _ => false
                }
              }
            }.toList, cfg.truncateOut)(mode).sortBy(_.length).take(2)

            // Only print if path mode is enabled
            val printWit = mode match {
              case NoOutputMode => false
              case MemoryOutputMode => true
              case DBOutputMode(_) => true
            }
            val allTerm = groupedResults.flatMap { res => res.terminals }
            val traceWitnesses = allTerm.flatMap {
              case PathNode(Qry(state, _, WitnessedQry(Some(explanation))), false) =>
                Some(state,explanation)
              case _ => None
            }
            if (printWit && outDir.nonEmpty) {
              pp.dumpDebugInfo(allTerm, "wit", outDir = outDir)(mode)
              traceWitnesses.zipWithIndex.foreach{
                case ((wit, state),ind) =>
                  (File(outDir.get) / s"explanation_${ind}")
                    .overwrite(s"${wit.toString} \n===========\n${state.toString}")
                  println(s"witness: \n ${wit.toString}")
                  println(s"last state: \n ${state.toString.replace(';', '\n')}")
              }
            }

            val depthChar: BounderUtil.DepthResult =
              BounderUtil.computeDepthOfWitOrLive(finalLiveNodes, QueryFinished)(mode)

            LocResult(initialQuery, id, loc, res, characterizedMaxPath, finalTime,
              depthChar, witnesses = live ++ witnessed, traceWitnesses.map(_._2).toList)
          }.toList
          grouped
        }catch {
          case e:OutOfMemoryError =>
            e.printStackTrace(new PrintWriter(System.err))
            Seq(LocResult(initialQuery, 0,
              AppLoc(SerializedIRMethodLoc("","",Nil), SerializedIRLineLoc(-1,"",0), true),
              resultSummary= Interrupted("OutOfMemoryError"),
              maxPathCharacterization = UnknownCharacterization,
              time = (System.nanoTime() - startTime)/1000000000,
              depthChar = DepthResult(-1,-1,-1, Interrupted("OutOfMemoryError")), witnesses = Nil,
              witnessExplanation = Nil
            ))
          case e:Throwable =>
            e.printStackTrace(new PrintWriter(System.err))
            Seq(LocResult(initialQuery, 0,
              AppLoc(SerializedIRMethodLoc("", "", Nil), SerializedIRLineLoc(-1,"", 0), true),
              resultSummary = Interrupted(e.toString),
              maxPathCharacterization = UnknownCharacterization,
              time = (System.nanoTime() - startTime) / 1000000000,
              depthChar = DepthResult(-1, -1, -1, Interrupted(e.toString)), witnesses = Nil,
              witnessExplanation = Nil
            ))
        }
      }
    } finally {
      println(s"analysis time(ms): ${(System.nanoTime() - startTime)}")
    }

  }
  def reduceResults(a:ResultSummary, b:ResultSummary):ResultSummary = {
    (a,b) match {
      case (Witnessed, _) => Witnessed
      case (_, Witnessed) => Witnessed
      case (_, Timeout) => Timeout
      case (Timeout, _) => Timeout
      case (v1,v2) if v1 == v2 => v1
      case (i1@Interrupted(r1), Interrupted(_)) if r1.contains("Witnessed") => i1
      case (Interrupted(_), i2@Interrupted(r2)) if r2.contains("Witnessed") => i2
      case (int:Interrupted, _) => int
      case (_,int:Interrupted) => int
    }
  }

  def groupConfigsInDirectory(maxCount:Int, dir:File):Unit = {
    val configs = dir.glob("*.json").toList
    assert(configs.nonEmpty, s"Found no configs in directory: ${dir}")
    val configsRead = configs.map{config =>
      read[RunConfig](config.contentAsString)
    }
    val grouped = configsRead.groupBy{_.tag}
    grouped.foreach{
      case (tag, configGroup) =>
        val refConfig = configGroup.head
        configGroup.foreach { other =>
          val errMsg = s"All configs should have same properties besides initial " +
            s"query. \nOther: ${other} \n Ref: ${refConfig}"
          assert(other.apkPath == refConfig.apkPath, errMsg)
          assert(other.outFolder == refConfig.outFolder, errMsg)
          assert(other.specSet == refConfig.specSet, errMsg)
          assert(other.tag == refConfig.tag, errMsg)
          assert(other.componentFilter == refConfig.componentFilter, errMsg)
          assert(other.tag == refConfig.tag)
        }
        val initialQueries = configGroup.flatMap { cfg => cfg.initialQuery }

        val iqGroups = initialQueries.grouped(maxCount)
        val cfgGroups = iqGroups.map { iqGroup => refConfig.copy(initialQuery = iqGroup) }
        cfgGroups.zipWithIndex.foreach{
          case (config, index) =>
            val outF = dir / s"${refConfig.tag.heuristicType}_${refConfig.tag.specRefinement}_grouped_${index}.json"
            assert(outF.notExists)
            outF.overwrite(write(config))
        }
    }
    configs.foreach { cfgFile => cfgFile.delete() }

  }

}

trait SpecSetOption{
  def getSpecSet():Set[LSSpec]
  def getDisallowSpecSet(): Set[LSSpec]
  def getSpecSpace():SpecSpace
}
object SpecSetOption{
  val testSpecSet: Map[String, Set[LSSpec]] = Map(
    "AntennaPod" -> Set(FragmentGetActivityNullSpec.getActivityNull,
      FragmentGetActivityNullSpec.getActivityNonNull,
      RxJavaSpec.call,
//      RxJavaSpec.subscribeDoesNotReturnNull,
      RxJavaSpec.subscribeIsUnique,
      LifecycleSpec.Fragment_activityCreatedOnlyFirst
    ))
  implicit val rw:RW[SpecSetOption] = upickle.default.readwriter[String].bimap[SpecSetOption](
    {
      case SpecFile(fname) => s"file:$fname"
      case TestSpec(name) => s"testSpec:$name"
      case TopSpecSet => s"top"
      case p:PickleSpec => write[PickleSpec](p)
    },
    str => str.split(":").toList match{
      case "file"::fname::Nil => SpecFile(fname)
      case "testSpec"::name::Nil => TestSpec(name)
      case "top"::Nil => TopSpecSet
      case a::_ if a.startsWith("{") =>
        read[PickleSpec](str)
      case _ => throw new IllegalArgumentException(s"Failure parsing SpecSetOption: $str")
    }
  )
}

@deprecated
case class SpecFile(fname:String) extends SpecSetOption {
  override def getSpecSet(): Set[LSSpec] = ???

  override def getDisallowSpecSet(): Set[LSSpec] = ???

  override def getSpecSpace(): SpecSpace = ???
}

case class TestSpec(name:String) extends SpecSetOption {
  override def getSpecSet(): Set[LSSpec] = SpecSetOption.testSpecSet(name)
  override def getDisallowSpecSet(): Set[LSSpec] = Set()

  override def getSpecSpace(): SpecSpace = ???
}

case class PickleSpec(specs:Set[LSSpec], disallow:Set[LSSpec] =Set(),
                      matcherSpace:Set[OAbsMsg] = Set()) extends SpecSetOption {
  override def getSpecSet(): Set[LSSpec] = specs
  override def getDisallowSpecSet(): Set[LSSpec] = disallow
  override def getSpecSpace():SpecSpace = new SpecSpace(specs, disallow, matcherSpace)
}
object PickleSpec{
  implicit val rw:RW[PickleSpec] = macroRW
  def mk(specSpace:SpecSpace):PickleSpec = {
    PickleSpec(specSpace.getSpecs, specSpace.getDisallowSpecs, specSpace.getMatcherSpace)
  }
}

case object TopSpecSet extends SpecSetOption {
  override def getSpecSet(): Set[LSSpec] = Set()
  override def getDisallowSpecSet(): Set[LSSpec] = Set()

  override def getSpecSpace(): SpecSpace = new SpecSpace(Set())
}

class ExperimentsDb(bounderJar:Option[String] = None){
  println("Initializing database")
  import scala.language.postfixOps
  private val home = scala.util.Properties.envOrElse("HOME", throw new IllegalStateException())
  private val jarPath = bounderJar.getOrElse(scala.util.Properties.envOrElse("BOUNDER_JAR",
    throw new RuntimeException("Bounder jar must be defined by BOUNDER_JAR environment variable")))
  private val bounderJarHash = BounderUtil.computeHash(jarPath)
  private val (hostname,port,database,username,password) = (File(home) / ".pgpass")
    .contentAsString.stripLineEnd.split(":").toList match{
      case hn::p::db::un::pw::Nil => (hn,p,db,un,pw)
      case _ => throw new IllegalStateException("Malformed pgpass")
    }
  // note use host.docker.internal:3333:postgres:postgres:[pass] for docker container ~/.pgpass on mac
  // use flag
  private val connectionUrl = s"jdbc:postgresql://${hostname}:${port}/${database}?user=${username}&password=${password}"
  val db = Database.forURL(connectionUrl, driver = "org.postgresql.Driver")
//  def runSql(q: String) = {
//    import slick.jdbc.H2Profile.api._
//    import slick.jdbc.GetResult
//    case class Count(n:Int)
//    implicit val getCountResult = GetResult(r => Count(r.<<))
//    val sql: SQLActionBuilder = sql"""select count(*) from results;"""
//    db.run(sql.as[Count].headOption)
//  }


  def loop() = {
    while(true) {
      val owner: String = BounderUtil.systemID
      println(s"identifier -- ${owner}")
      val job: Option[JobRow] = acquireJob(owner)
      if(job.isDefined) {
        println(s"--got job: ${job.get}")
        processJob(job.get)
      }else {
        println("--no jobs waiting")
      }

      Thread.sleep(5000)
    }

  }
  def processJob(jobRow: JobRow) = {
    val iProcess = (baseDir:File) =>
      try {
        println(s"working directory: ${baseDir.toString}")
        val cfg = read[RunConfig](jobRow.config)
        val apkId = cfg.apkPath.replace("${baseDir}","")
        val apkPath = baseDir / "target.apk"

        println(s"downloading apk: $apkId")
        val apkStartTime = Instant.now.getEpochSecond
        if(!downloadApk(apkId, apkPath))
          throw new RuntimeException("Failed to download apk")
        println(s"done downloading apk: ${Instant.now.getEpochSecond - apkStartTime}")

        // check if inputs are current and download them otherwise
        val inputId = jobRow.inputid
        val bounderJar = baseDir / "bounder.jar"
        val specFile = baseDir / "specFile.txt"
        getInputs(inputId, bounderJar, specFile)
        //TODO: probably cache these

        // create directory for output
        val outF = File(cfg.outFolder.get.replace("${baseDirOut}",baseDir.toString))
        outF.createDirectories()
        // TODO: read results of new structure
        val specContents = specFile.contentAsString
        val runCfg:RunConfig = cfg.copy(apkPath = apkPath.toString)
        assert(specContents.trim == "", "Job level spec input deprecated, specify spec in config")
          //specSet = if(specContents.trim == "") TopSpecSet else read[PickleSpec](specContents))
        val cfgFile = (baseDir / "config.json")
        cfgFile.append(write(runCfg))
        val z3Override = if(BounderUtil.mac)
          s"""-Djava.library.path="${BounderUtil.dy}""""
        else
          ""
        println("Starting Verifier")
        setJobStartTime(jobRow.jobId)
        val outputFlag:String = jobRow.jobTag.flatMap{
          case ts:String if ts != "" => read[ExpTag](ts).other.split(",").headOption
          case _ => None
        }.map{v => if(v.trim() != "")s" -o ${v}" else ""}.getOrElse("")
        val cmd = s"java ${z3Override} -Xmx16g -jar ${bounderJar.toString} -m verify -c ${cfgFile.toString} " +
          s"-u ${outF.toString} ${outputFlag}"
        // Run the command for this job
        // kill jobs that take 2x the query time limit
        // jobs may take longer than the time limit if soot z3 or another external library gets stuck
        BounderUtil.runCmdTimeout(cmd, baseDir, runCfg.timeLimit * 9) match {
          case BounderUtil.RunTimeout =>
            finishFail(jobRow.jobId, "Subprocess Timeout")
          case BounderUtil.RunSuccess => {
            setEndTime(jobRow.jobId)
            println("Finished Verifier Writing Results")
            val resDir = ResultDir(jobRow.jobId, baseDir, cfg.tag)
            val stdoutF = baseDir / "stdout.txt"
            val stdout = if (stdoutF.exists()) stdoutF.contentAsString else ""
            val stderrF = baseDir / "stderr.txt"
            val stderr = if (stderrF.exists()) stderrF.contentAsString else ""
            // Delete files that aren't needed working directory will be uploaded
            val uploadStartTime = Instant.now.getEpochSecond
            println("uploading results")
            bounderJar.delete()
            finishSuccess(resDir, stdout, stderr)
            println(s"done uploading results: ${Instant.now.getEpochSecond - uploadStartTime}")
          }
          case BounderUtil.RunFail =>
            val stdoutF = baseDir / "stdout.txt"
            val stdout = if (stdoutF.exists()) stdoutF.contentAsString else ""
            val stderrF = baseDir / "stderr.txt"
            val stderr = if (stderrF.exists()) stderrF.contentAsString else ""
            finishFail(jobRow.jobId, s"Non-zero exit code. StdErr: \n${stderr}\n---\n StdOut:\n${stdout}")
        }

      }catch{
        case t:Throwable =>
          println(s"exception ${t.toString}")
          val sr = new StringWriter()
          val pr = new PrintWriter(sr)
          t.printStackTrace(pr)
          val exn = sr.toString
          finishFail(jobRow.jobId, t.toString + "\n" + exn)

    }
    val useShm = Properties.envOrElse("USESHM", "false").toBoolean
    if(useShm && File("/dev/shm").exists()){
      //If on linux system, use ramdisk
      val outDir = File("/dev/shm/bounder_out_tmp")
      try{
        outDir.createDirectory()
        iProcess(outDir)
      }finally{
        outDir.delete()
      }

    }else{
      //Use temp directory
      File.usingTemporaryDirectory(){iProcess}
    }

  }


  //  CREATE TABLE jobs(
//    id integer,
//    status varchar(20),
//    config varchar,
//    started timestamp without time zone,
//    ended timestamp without time zone,
//    owner varchar
//      PRIMARY KEY(id)
//  );
  private val jobQry = TableQuery[Jobs]
  def createJob(config:File, jobTag:Option[String], inputs:Int): Unit ={
    val configContents = config.contentAsString
    Await.result(
      db.run(jobQry += JobRow(0, "new", configContents, None, None, "",Some(""),Some(""), jobTag,inputs)),
      40 seconds)
  }
  def setJobStartTime(id:Int) = {
    val date = new Date()
    val startTime = Some(new Timestamp(date.getTime))
    val q = for(
      j <- jobQry if j.jobId === id
    ) yield j.started
    Await.result(db.run(q.update(startTime)), 300 seconds)
  }
  def setEndTime(id:Int) = {
    val date = new Date()
    val endTime = Some(new Timestamp(date.getTime))
    val q = for(
      j <- jobQry if j.jobId === id
    )yield j.ended
    Await.result(db.run(q.update(endTime)), 300 seconds)
  }

  def acquireJob2(owner: String): Option[JobRow] = {
    // TODO: test this impl and swap out for acquireJob later

    val dbio = for {
      pendingJob <- jobQry.filter(_.status === "new").take(1).forUpdate.result.headOption
      maybeRow <-
        pendingJob.map { row =>
          jobQry.filter(_.jobId === row.jobId)
            .map(j => (j.owner, j.status)).update((owner, "acquired")).map(_ => Some(row))
        }.getOrElse(DBIO.successful(None))
    } yield maybeRow
    Await.result(db.run(dbio.transactionally).recover {
      case _: java.sql.SQLException => None
    }, 30.seconds)
  }
  def acquireJob(owner:String): Option[JobRow] = {
    import slick.jdbc.H2Profile.api._
    val getIdQ = sqlu"""
        With cte AS (
            SELECT * from jobs WHERE status='new' ORDER BY id LIMIT 1
            FOR UPDATE SKIP LOCKED
            )
        UPDATE jobs s
        SET status='acquired',owner=${owner}
        FROM cte
        WHERE s.id = cte.id
          """.transactionally
    Await.result(db.run(getIdQ), 300 seconds)

    val q = for(
      j <- jobQry if j.owner === owner && j.status==="acquired"
    ) yield j
    val pendingJob = Await.result(
      db.run(q.take(1).result), 300 seconds
    )
    if(pendingJob.isEmpty) {
      None
    }else if(pendingJob.size > 1){
      throw new IllegalStateException(s"got multiple pending jobs: ${pendingJob}")
    }else{
      val row = pendingJob.head
      val updQ = jobQry.filter(j => j.jobId === row.jobId && j.status === "new")
          .map(v => (v.owner,v.status)).update(owner, "acquired")
      Await.result(db.run(updQ.transactionally).map { res =>
        Some(row)
      }.recover {
        case _: java.sql.SQLException => None
      }, 300 seconds)
    }
  }
  case class JobRow(jobId:Int, status:String, config:String,started:Option[Timestamp],
                    ended:Option[Timestamp], owner:String, stderr:Option[String],
                    stdout:Option[String], jobTag:Option[String],inputid:Int)
//  CREATE TABLE jobs(
//    id SERIAL PRIMARY KEY,
//    status varchar,
//    config varchar,
//    started timestamp without time zone,
//    ended timestamp without time zone,
//    owner varchar,
//    stderr varchar,
//    stdout varchar,
//      inputid Int
//  );
  class Jobs(tag:Tag) extends Table[JobRow](tag,"jobs"){
    val jobId = column[Int]("id",O.PrimaryKey,O.AutoInc)
    val status = column[String]("status")
    val config = column[String]("config")
    val started = column[Option[Timestamp]]("started")
    val ended = column[Option[Timestamp]]("ended")
    val owner = column[String]("owner")
    val stderr = column[Option[String]]("stderr")
    val stdout = column[Option[String]]("stdout")
    val jobtag = column[Option[String]]("jobtag")
    val inputid = column[Int]("inputid")
    def * = (jobId,status,config, started, ended, owner,stderr,stdout,jobtag,inputid) <> (JobRow.tupled, JobRow.unapply)
  }
  //  CREATE TABLE results(
  //    id SERIAL PRIMARY KEY,
  //    jobid integer,
  //    qry varchar,
  //    result varchar,
  //    stderr varchar,
  //    stdout varchar,
  //    resultdata int,
  //    apkHash varchar,
  //    bounderJarHash varchar,
  //    owner varchar
  //  );
  case class ResultDir(jobId:Int, f:File, jobTag:ExpTag){
    def getDBResults :List[DBResult]= {
      val apk = f / "target.apk"
      val apkHash = BounderUtil.computeHash(apk.toString)
      apk.delete()
      val resultSummaries = f.glob("**/result_*.txt").map{resF =>
        // [qry,id,loc, res, time]
        read[LocResult](resF.contentAsString)
      }
      val resDataId:Option[Int] = {
        val outDat = f.zip()
        val d = Some(createData(outDat))
        outDat.delete()
        d
      }
      resultSummaries.map { rs =>
        val resultRow = ujson.Obj(
          "summary" -> ujson.Str(write[ResultSummary](rs.resultSummary)),
          "maxPathCh" -> ujson.Str(write[MaxPathCharacterization](rs.maxPathCharacterization)),
          "depth" -> write(rs.depthChar),
          "wit" -> ujson.Arr(rs.witnesses)
        ).toString
        DBResult(id = 0, jobid = jobId,qry = write(rs.q), loc = write(rs.loc), result = resultRow, queryTime = rs.time
          ,resultData = resDataId, apkHash = apkHash,
          bounderJarHash = bounderJarHash, owner = BounderUtil.systemID, jobTag = Some(write[ExpTag](jobTag)))
      }
    }.toList
  }
  case class DBResult(id:Int, jobid:Int, qry:String, loc:String, result:String, queryTime:Long,
                      resultData:Option[Int], apkHash:String, bounderJarHash:String, owner:String,
                      jobTag: Option[String])

  class Results(tag:Tag) extends Table[DBResult](tag,"results"){
    val id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    val jobId = column[Int]("jobid")
    val qry = column[String]("qry") // query can represent multiple locs
    val loc = column[String]("loc") // specific location in code where
    val result = column[String]("result")
    val queryTime = column[Long]("querytime")
    val resultData = column[Option[Int]]("resultdata")
    val apkHash = column[String]("apkhash")
    val bounderJarHash = column[String]("bounderjarhash")
    val owner = column[String]("owner")
    val jobtag = column[Option[String]]("jobtag")
    val * = (id,jobId,qry,loc,result,queryTime, resultData, apkHash,
      bounderJarHash, owner, jobtag) <> (DBResult.tupled, DBResult.unapply)
  }
  val resultsQry = TableQuery[Results]
  def getCCParams(cc: AnyRef) =
    cc.getClass.getDeclaredFields.foldLeft(Map.empty[String, Any]) { (a, f) =>
      f.setAccessible(true)
      a + (f.getName -> f.get(cc))
    }
  def downloadResults(outFolder:File, filterResult:String, limit:Option[Int]) = {
    //    val rand = SimpleFunction.nullary[Double]("rand")
    val getJobs = for {
      job <- jobQry
      res <- resultsQry if job.jobId === res.jobId && (res.result like filterResult)
    } yield (job,res)
    val jobRows = Await.result(db.run(getJobs.result), 90 seconds)

    val limitJobRows = if(limit.isDefined) jobRows.take(limit.get) else jobRows

    val dataToDownload = limitJobRows.map{case (job, res) =>
      val cfg = read[RunConfig](job.config)
      val sOut:String = cfg.outFolder.get.replace("${baseDirOut}","").dropWhile(_ == '/')
      outFolder.createDirectoryIfNotExists(createParents = true)
      val currentOut = File(outFolder.toString + "/" +  sOut)
      //println("out folder: " + currentOut)
      currentOut.createDirectoryIfNotExists(createParents = true)

      val resDir = currentOut / s"res_${res.id}"
//      (resDir / "id").append(res.id.toString)
      resDir.createIfNotExists(asDirectory = true)
      getCCParams(res).foreach{case (k,v) => (resDir / s"res_$k").append(v.toString)}
      getCCParams(job).foreach{case (k,v) => (resDir / s"job_$k").append(v.toString)}

      (res.resultData, currentOut)
    }.toSet

    dataToDownload.foreach{
      case (Some(d), out) =>
        //println(s"downloading data $d to directory $out")
        val dataDir = out / s"data_$d"
        dataDir.createIfNotExists(asDirectory = true)
        val data = (dataDir / "data.zip")
        getData(d, data)
      case (None,_) => ()
    }
  }
  def finishSuccess(d : ResultDir, stdout:String, stderr:String): Int = {
    val owner: String = BounderUtil.systemID
    val iamowner = for(
      j <- jobQry if j.jobId === d.jobId
    ) yield (j.jobId, j.owner)
    val jobRows = Await.result(db.run(iamowner.result), 300 seconds)
    if(jobRows.size != 1 || jobRows.head._2 != owner)
      throw new IllegalStateException(s"Concurrency exception, I am $owner and found " +
        s"jobs Jobs: ${jobRows.mkString(";")} ")

    // check that no results already exist
    val existingResDataQ = for(
      j <-resultsQry if j.jobId === d.jobId
    ) yield (j.jobId)
    val existingResData = Await.result(db.run(existingResDataQ.result), 300 seconds)
    assert(existingResData.isEmpty, s"existing results data nonempty, ${d}")

    // upload results
    val resData = d.getDBResults
    resData.foreach{d =>
      Await.result(db.run(resultsQry += d), 300 seconds)
    }
    // set completed
    val q = for(
      j <- jobQry if j.jobId === d.jobId
    ) yield (j.status, j.stdout, j.stderr)
    Await.result(db.run(q.update(("completed",Some(stdout),Some(stderr)))), 300 seconds)
  }
  def finishFail(id:Int, exn:String): Int = {
    val q = for(
      j <- jobQry if j.jobId === id
    ) yield j.status
    Await.result(db.run(q.update("failed: " + exn)), 300 seconds)
  }
  //  CREATE TABLE resultdata(
  //    id integer,
  //    data bytea,
  //    PRIMARY KEY(id)
  //  )
  val resultDataQuery = TableQuery[ResultData]
  def createData(data:File):Int = {
    val dataBytes = data.loadBytes
//    val insertQuery = items returning items.map(_.id) into ((item, id) => item.copy(id = id))
    val insertQuery = resultDataQuery returning resultDataQuery.map(_.id) into ((data,id) =>  id)
    Await.result(db.run(insertQuery += (0,dataBytes)), 90 seconds)
  }
  def getData(id:Int, outFile:File) = {
    val qry = for {
      row <- resultDataQuery if row.id === id
    } yield row.data
    val bytes: Seq[Array[Byte]] = Await.result(
      db.run(qry.take(1).result), 60 seconds
    )
    if(bytes.size == 1){
      outFile.writeByteArray(bytes.head)
      true
    } else
      false
  }
  class ResultData(tag:Tag) extends Table[(Int,Array[Byte])](tag,"resultdata"){
    val id = column[Int]("id", O.PrimaryKey,O.AutoInc)
    val data = column[Array[Byte]]("data")
    def * = (id,data)
  }

  //CREATE TABLE apks (apkname text, img bytea);
  private val apkQry = TableQuery[ApkTable]
  def uploadApk(name:String, apkFile:File):Int = {
    val apkDat = apkFile.loadBytes
    Await.result(
      db.run(apkQry += (name,apkDat)),
      300 seconds
    )
  }
  def downloadApk(name:String, outFile:File) :Boolean= {
    println(s"downloading apk: ${name}")
    val qry = for {
      row <- apkQry if row.name === name
    } yield row.img
    val bytes: Seq[Array[Byte]] = Await.result(
      db.run(qry.take(1).result), 600 seconds
    )
    if(bytes.size == 1){
      outFile.writeByteArray(bytes.head)
      true
    } else
      false
  }
  class ApkTable(tag:Tag) extends Table[(String,Array[Byte])](tag, "apks"){
    def name = column[String]("apkname")
    def img = column[Array[Byte]]("img")
    def * = (name,img)
  }

  // config - points to spec and jar in apktable (TODO: rename apktable to filestore or something)
  //  CREATE TABLE config (
  //    id SERIAL PRIMARY KEY,
  //    bounderjar text,
  //    specfile text,
  //    notes text
  //  );
  case class RunInputs(id:Int, bounderjar:String, specfile:String, notes:String)
  // values in bounderjar and specfile are md5hash of files
  class Inputs(tag:Tag) extends Table[RunInputs](tag,"inputs"){
    def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def bounderjar = column[String]("bounderjar")
    def specfile = column[String]("specfile")
    def notes = column[String]("notes")
    def * = (id,bounderjar, specfile, notes) <> (RunInputs.tupled, RunInputs.unapply)
  }
  private val inputsQuery = TableQuery[Inputs]
  def createConfig(specFile:File, bounderJar:File, notes:String):Int = {
    val bounderJarHash =  BounderUtil.computeHash(bounderJar.toString)
    val specFileHash =  BounderUtil.computeHash(specFile.toString)
    uploadApk("jar_" + bounderJarHash, bounderJar)
    uploadApk("spec_" + specFileHash, specFile)
    val runInputs = RunInputs(0,bounderJarHash, specFileHash, notes)

    val insertQuery = inputsQuery returning inputsQuery.map(_.id) into ((_,id) =>  id)
    Await.result(db.run(insertQuery += runInputs), 300 seconds)
  }
  def getInputs(id:Int, bounderJar:File, specFile:File) = {
    val q1 = for {
      inp <- inputsQuery if inp.id === id
    } yield (inp.bounderjar,inp.specfile)
    val inputFiles = Await.result(db.run(q1.result), 300 seconds)
    assert(inputFiles.size == 1)
    val (bounderJarHash, specFileHash) = inputFiles.head
    downloadApk("jar_" + bounderJarHash, bounderJar)
    downloadApk("spec_" + specFileHash, specFile)
  }
}
