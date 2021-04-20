package edu.colorado.plv.bounder

import java.io.{PrintWriter, StringWriter}
import java.sql.Timestamp
import java.time.Instant
import java.util.Date

import better.files.File
import edu.colorado.plv.bounder.BounderUtil.{Proven, ResultSummary, Timeout, Unreachable, Witnessed}
import edu.colorado.plv.bounder.Driver.{Default, LocResult, RunMode}
import edu.colorado.plv.bounder.ir.{JimpleFlowdroidWrapper, Loc}
import edu.colorado.plv.bounder.lifestate.LifeState.LSSpec
import edu.colorado.plv.bounder.lifestate.{ActivityLifecycle, FragmentGetActivityNullSpec, LifeState, RxJavaSpec, SpecSpace}
import edu.colorado.plv.bounder.solver.ClassHierarchyConstraints
import edu.colorado.plv.bounder.symbolicexecutor.state._
import edu.colorado.plv.bounder.symbolicexecutor.{CHACallGraph, SparkCallGraph, SymbolicExecutor, SymbolicExecutorConfig, TransferFunctions}
import scopt.OParser

import scala.concurrent.Await
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.SQLActionBuilder
import soot.SootMethod
import ujson.{Value, validate}
import upickle.core.AbortException
import upickle.default.{macroRW, read, write, ReadWriter => RW}

import scala.concurrent.duration._
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.matching.Regex

case class Action(mode:RunMode = Default,
                  baseDirOut: Option[String] = None,
                  baseDirApk:Option[String] = None,
                  config: RunConfig = RunConfig(),
                  filter:Option[String] = None, // for making allderef queries - only process classes beginning with
                  tag:Option[String] = None
                 ){
  val baseDirVar = "${baseDir}"
  val outDirVar = "${baseDirOut}"
  def getApkPath:String = baseDirApk match{
    case Some(baseDir) => {
//      assert(config.apkPath.contains(baseDirVar),
//        s"Apk path has no $baseDirVar to substitute.  APK path value: ${config.apkPath}")
      config.apkPath.replace(baseDirVar, baseDir)
    }
    case None => {
      assert(!config.apkPath.contains(baseDirVar))
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

case class RunConfig(apkPath:String = "",
                     outFolder:Option[String] = None,
                     componentFilter:Option[Seq[String]] = None,
                     specSet: SpecSetOption = TopSpecSet,
                     initialQuery: List[InitialQuery] = Nil,
                     limit:Int = 100,
                     samples:Int = 5,
                     tag:String = "",
                     timeLimit:Int = 600
                    ){
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

  }
  sealed trait RunMode
  case object Verify extends RunMode
  case object Info extends RunMode
  case object Default extends RunMode
  case object SampleDeref extends RunMode
  case object ReadDB extends RunMode
  case object ExpLoop extends RunMode
  case object MakeAllDeref extends RunMode

  def readDB(outFolder:File, findNoPred:Boolean = false): Unit = {
    val dbPath = outFolder / "paths.db"
    val db = DBOutputMode(dbPath.toString())
    val liveNodes: Set[IPathNode] = db.getTerminal().map(v=>v)
    val pp = new PrettyPrinting(db)
    pp.dumpDebugInfo(liveNodes, "out", Some(outFolder.toString))

    if(findNoPred){
      val noPredNodes: Set[IPathNode] = db.getNoPred().map(v=>v)
      pp.dumpDebugInfo(noPredNodes, "noPred", Some(outFolder.toString))
    }
  }

  def main(args: Array[String]): Unit = {
    val builder = OParser.builder[Action]
    val parser = {
      import builder._
      OParser.sequence(
        programName("Bounder"),
        opt[String]('m',"mode").required().text("run mode [verify, info, sampleDeref]").action{
          case ("verify",c) => c.copy(mode = Verify)
          case ("info",c) => c.copy(mode = Info)
          case ("sampleDeref",c) => c.copy(mode = SampleDeref)
          case ("readDB",c) => c.copy(mode = ReadDB)
          case ("expLoop",c) => c.copy(mode = ExpLoop)
          case ("makeAllDeref",c) => c.copy(mode = MakeAllDeref)
          case (m,_) => throw new IllegalArgumentException(s"Unsupported mode $m")
        },
        opt[String]('b', "baseDirApk").optional().text("Substitute for ${baseDir} in config file")
          .action((v,c) => c.copy(baseDirApk = Some(v))),
        opt[String]('u', "baseDirOut").optional().text("Substitute for ${baseDirOut} in config file")
          .action((v,c) => c.copy(baseDirOut = Some(v))),
        opt[java.io.File]('c', "config").optional()
          .text("Json config file, use full option names as config keys.").action{(v,c) => {
            try {
              val readConfig = read[RunConfig](v)
//              readConfig.copy(baseDir = c.baseDir, baseDirOut = c.baseDirOut)
              c.copy(config = readConfig)
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
        opt[String]('f',"filter").optional()
          .text("Package filter for sampling(currently only supported by makeAllDeref)")
          .action((v,c) => c.copy(filter = Some(v))),
        opt[String]('t', "tag").optional()
          .text("Tag for experiment, recorded when running")
          .action((v,c) => c.copy(tag = Some(v)))
      )
    }
    OParser.parse(parser, args, Action()) match{
      case Some(act) => runAction(act)
      case None => throw new IllegalArgumentException("Argument parsing failed")
    }
  }

  def expLoop(act: Action): Unit = {
    val expDb = new ExperimentsDb
    expDb.loop()
    println()
  }
  // [qry,id,loc, res, time]
  case class LocResult(q:InitialQuery, sqliteId:Int, loc:Loc, resultSummary: ResultSummary, time:Long)
  object LocResult{
    implicit var rw:RW[LocResult] = macroRW
  }

  def runAction(act:Action):Unit = act match{
    case act@Action(Verify,_, _, cfg,_,_) =>
      val componentFilter = cfg.componentFilter
      val specSet = cfg.specSet
        //        val cfgw = write(cfg)
      val apkPath = act.getApkPath
      val outFolder: String = act.getOutFolder
        // Create output directory if not exists
        // TODO: move db creation code to better location
      File(outFolder).createIfNotExists(asDirectory = true)
      val initialQuery = cfg.initialQuery
      if(initialQuery.isEmpty)
        throw new IllegalArgumentException("Initial query must be defined for verify")
      val stepLimit = cfg.limit
      val outFile = (File(outFolder) / "paths.db")
      if(outFile.exists) {
        implicit val opt = File.CopyOptions(overwrite = true)
        outFile.moveTo(File(outFolder) / "paths.db1")
      }
      DBOutputMode(outFile.canonicalPath)
      val pathMode = DBOutputMode(outFile.canonicalPath)
      val res: Seq[(InitialQuery,Int, Loc, ResultSummary, Long)] =
        runAnalysis(cfg,apkPath, componentFilter,pathMode, specSet.getSpecSet(),stepLimit, initialQuery)
      res.zipWithIndex.foreach { case (iq, ind) =>
        val resFile = File(outFolder) / s"result_${ind}.txt"
        resFile.overwrite(write(LocResult(iq._1,iq._2, iq._3,
          iq._4, iq._5))) // [qry,id,loc, res, time] //TODO: less dumb serialized format
//        resFile.append(iq._2)
      }
    case act@Action(SampleDeref,_,_,cfg,_,_) =>
      //TODO: set base dir
      sampleDeref(cfg, act.getApkPath, act.getOutFolder)
    case act@Action(ReadDB,_,_,_,_,_) =>
      readDB(File(act.getOutFolder))
    case act@Action(ExpLoop,_, _,_,_,_) =>
      expLoop(act)
    case act@Action(MakeAllDeref,_,_,cfg,_,tag) =>
      makeAllDeref(act.getApkPath, act.filter, File(act.getOutFolder),cfg, tag)
    case v => throw new IllegalArgumentException(s"Invalid action: $v")
  }
  def detectProguard(apkPath:String):Boolean = {
    import sys.process._
    val cmd = (File(BounderSetupApplication.androidHome) / "tools" /"bin"/"apkanalyzer").toString
    var stdout:List[String] = List()
    val stderr = new StringBuilder

    val status = s"$cmd -h dex packages ${apkPath.replace(" ","\\ ")}".!(ProcessLogger(v => {
      stdout = v::stdout
    }, stderr append _))
    if(status != 0){
      throw new IllegalArgumentException(s"apk: $apkPath  error: $stderr")
    }
    stdout.exists(v => v.contains("a.a.a."))
  }

  def makeAllDeref(apkPath:String, filter:Option[String],
                   outFolder:File, cfg:RunConfig, tag:Option[String]) = {
    val callGraph = CHACallGraph
    val w = new JimpleFlowdroidWrapper(apkPath, callGraph)
    val transfer = (cha: ClassHierarchyConstraints) =>
      new TransferFunctions[SootMethod, soot.Unit](w, new SpecSpace(Set()), cha)
    val config = SymbolicExecutorConfig(
      stepLimit = Some(0), w, transfer, component = None)
    val symbolicExecutor: SymbolicExecutor[SootMethod, soot.Unit] = config.getSymbolicExecutor
    val appClasses = symbolicExecutor.appCodeResolver.appMethods.map(m => m.classType)
    val filtered = appClasses.filter(c => filter.forall(c.startsWith))
    val initialQueries = filtered.map(c => AllReceiversNonNull(c))
    initialQueries.foreach{q =>
      //TODO: should we group more classes together in a job?
      val cfgOut = cfg.outFolder.get
      val cfg2 = cfg.copy(initialQuery = List(q),
        tag = tag.getOrElse(""), outFolder = Some(cfgOut +"/"+ q.className))
      val fname = outFolder / s"${q.className}.json"
      if(fname.exists())fname.delete()
      fname.append(write(cfg2))
    }
  }
  def sampleDeref(cfg: RunConfig, apkPath:String, outFolder:String) = {
    val n = cfg.samples
    val callGraph = CHACallGraph
    val w = new JimpleFlowdroidWrapper(apkPath, callGraph)
    val transfer = (cha: ClassHierarchyConstraints) =>
      new TransferFunctions[SootMethod, soot.Unit](w, new SpecSpace(Set()), cha)
    val config = SymbolicExecutorConfig(
      stepLimit = Some(n), w, transfer, component = None)
    val symbolicExecutor: SymbolicExecutor[SootMethod, soot.Unit] = config.getSymbolicExecutor

    val queries = (0 until n).map{_ =>
      val appLoc = symbolicExecutor.appCodeResolver.sampleDeref()
      val name = appLoc.method.simpleName
      val clazz = appLoc.method.classType
      val line = appLoc.line.lineNumber
      ReceiverNonNull(clazz, name, line)
    }.toList
    val outName = s"sample"
    val f = File(outFolder) / s"$outName.json"
    val writeCFG = cfg.copy(initialQuery = queries,
      outFolder = cfg.outFolder.map(v => v + "/" + outName))
    if(f.exists()) f.delete()
    f.createFile()
    f.write(write(writeCFG))
  }

  def dotMethod(apkPath:String, matches:Regex) = {
    val callGraph = CHACallGraph
    //      val callGraph = FlowdroidCallGraph // flowdroid call graph immediately fails with "unreachable"
    val w = new JimpleFlowdroidWrapper(apkPath, callGraph)
    val transfer = (cha: ClassHierarchyConstraints) =>
      new TransferFunctions[SootMethod, soot.Unit](w, new SpecSpace(Set()), cha)
    val config = SymbolicExecutorConfig(
      stepLimit = Some(0), w, transfer, component = None)
    val symbolicExecutor: SymbolicExecutor[SootMethod, soot.Unit] = config.getSymbolicExecutor
    //TODO:
  }
  def runAnalysis(cfg:RunConfig, apkPath: String, componentFilter:Option[Seq[String]], mode:OutputMode,
                  specSet: Set[LSSpec], stepLimit:Int,
                  initialQueries: List[InitialQuery]): List[(InitialQuery,Int,Loc,ResultSummary,Long)] = {
    val startTime = System.currentTimeMillis()
    try {
      //TODO: read location from json config
//      val callGraph = CHACallGraph
      val callGraph = SparkCallGraph
//      val callGraph = FlowdroidCallGraph // flowdroid call graph immediately fails with "unreachable"
      val w = new JimpleFlowdroidWrapper(apkPath, callGraph)
      val transfer = (cha: ClassHierarchyConstraints) =>
        new TransferFunctions[SootMethod, soot.Unit](w, new SpecSpace(specSet), cha)
      val config = SymbolicExecutorConfig(
        stepLimit = Some(stepLimit), w, transfer, component = componentFilter, outputMode = mode,
        timeLimit = cfg.timeLimit)
      val symbolicExecutor: SymbolicExecutor[SootMethod, soot.Unit] = config.getSymbolicExecutor
//      val query = Qry.makeCallinReturnNull(symbolicExecutor, w,
//        "de.danoeh.antennapod.fragment.ExternalPlayerFragment",
//        "void updateUi(de.danoeh.antennapod.core.util.playback.Playable)", 200,
//        callinMatches = ".*getActivity.*".r)
      initialQueries.flatMap{ initialQuery =>
        val query: Set[Qry] = initialQuery.make(symbolicExecutor, w)
        val out = new ListBuffer[String]()
        val initialize: Set[IPathNode] => Int = mode match {
          case mode@DBOutputMode(_) => (startingNode: Set[IPathNode]) =>
            val id = mode.initializeQuery(startingNode, cfg, initialQuery)
            val tOut = s"initial query: $initialQuery   id: $id"
            println(tOut)
            out += tOut
            id
          case _ => (_: Set[IPathNode]) => 0
        }

        //        (Int,Loc,Set[IPathNode],Long)
        val results: Set[symbolicExecutor.QueryData] = symbolicExecutor.run(query, initialize)

        val allRes = results.map { res =>
          mode match {
            case m@DBOutputMode(_) =>
              val interpretedRes = (res.queryId, res.location,
                BounderUtil.interpretResult(res.terminals, res.result),res.runTime)
              val tOut = s"id: ${res.queryId}   result: ${interpretedRes}"
              println(tOut)
              out += tOut
              m.writeLiveAtEnd(res.terminals, res.queryId, interpretedRes.toString)
              interpretedRes
            case _ => (res.queryId, res.location,BounderUtil.interpretResult(res.terminals, res.result), res.runTime)
          }
        }
        val grouped = allRes.groupBy(v => (v._1,v._2)).map{case ((id,loc),groupedResults) =>
          val finalRes = groupedResults.map(_._3).reduce(reduceResults)
          val finalTime = groupedResults.map(_._4).sum
          (initialQuery,id,loc,finalRes,finalTime)
        }.toList
        grouped
      }
    } finally {
      println(s"analysis time: ${(System.currentTimeMillis() - startTime) / 1000} seconds")
    }

  }
  def reduceResults(a:ResultSummary, b:ResultSummary):ResultSummary = {
    (a,b) match {
      case (Witnessed, _) => Witnessed
      case (_, Witnessed) => Witnessed
      case (_, Timeout) => Timeout
      case (Timeout, _) => Timeout
      case (Unreachable, v2) => v2
      case (v1, Unreachable) => v1
      case (v1,v2) if v1 == v2 => v1
    }
  }

}

trait SpecSetOption{
  def getSpecSet():Set[LSSpec]
}
object SpecSetOption{
  val testSpecSet: Map[String, Set[LSSpec]] = Map(
    "AntennaPod" -> Set(FragmentGetActivityNullSpec.getActivityNull,
      FragmentGetActivityNullSpec.getActivityNonNull,
      RxJavaSpec.call,
//      RxJavaSpec.subscribeDoesNotReturnNull,
      RxJavaSpec.subscribeIsUnique,
      ActivityLifecycle.Fragment_activityCreatedOnlyFirst
    ))
  implicit val rw:RW[SpecSetOption] = upickle.default.readwriter[String].bimap[SpecSetOption](
    {
      case SpecFile(fname) => s"file:$fname"
      case TestSpec(name) => s"testSpec:$name"
      case TopSpecSet => s"top"
    },
    str => str.split(":").toList match{
      case "file"::fname::Nil => SpecFile(fname)
      case "testSpec"::name::Nil => TestSpec(name)
      case "top"::Nil => TopSpecSet
      case _ => throw new IllegalArgumentException(s"Failure parsing SpecSetOption: $str")
    }
  )
}
case class SpecFile(fname:String) extends SpecSetOption {
  //TODO: write parser for spec set
  override def getSpecSet(): Set[LSSpec] = LifeState.parseSpecFile(File(fname).contentAsString)
}

case class TestSpec(name:String) extends SpecSetOption {
  override def getSpecSet(): Set[LSSpec] = SpecSetOption.testSpecSet(name)
}

case object TopSpecSet extends SpecSetOption {
  override def getSpecSet(): Set[LSSpec] = Set()
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
  def runSql(q: String) = {
    import slick.jdbc.H2Profile.api._
    import slick.jdbc.GetResult
    case class Count(n:Int)
    implicit val getCountResult = GetResult(r => Count(r.<<))
    val sql: SQLActionBuilder = sql"""select count(*) from results;"""
    db.run(sql.as[Count].headOption)
  }


  def loop() = {
    while(true) {
      val owner: String = BounderUtil.systemID()
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
      File.usingTemporaryDirectory(){(baseDir:File) =>
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
          val runCfg = cfg.copy(apkPath = apkPath.toString, specSet = SpecFile(specFile.toString) )
          val cfgFile = (baseDir / "config.json")
          cfgFile.append(write(runCfg))
          val z3Override = if(BounderUtil.mac)
            s"""-Djava.library.path="${BounderUtil.dy}""""
          else
            ""
          println("Starting Verifier")
          setJobStartTime(jobRow.jobId)
          val cmd = s"java ${z3Override} -jar ${bounderJar.toString} -m verify -c ${cfgFile.toString} -u ${outF.toString}"
          BounderUtil.runCmdFileOut(cmd, baseDir)
          setEndTime(jobRow.jobId)
          println("Finished Verifier Writing Results")
          val resDir = ResultDir(jobRow.jobId, baseDir, if(cfg.tag!="") Some(cfg.tag) else None)
          val stdoutF = baseDir / "stdout.txt"
          val stdout = if(stdoutF.exists()) stdoutF.contentAsString else ""
          val stderrF = baseDir / "stderr.txt"
          val stderr = if(stderrF.exists())stderrF.contentAsString else ""
          // Delete files that aren't needed working directory will be uploaded
          val uploadStartTime = Instant.now.getEpochSecond
          println("uploading results")
          bounderJar.delete()
          finishSuccess(resDir,stdout, stderr)
          println(s"done uploading results: ${Instant.now.getEpochSecond - uploadStartTime}")
        }catch{
          case t:Throwable =>
            println(s"exception ${t.toString}")
            val sr = new StringWriter()
            val pr = new PrintWriter(sr)
            t.printStackTrace(pr)
            val exn = sr.toString
            finishFail(jobRow.jobId, t.toString + "\n" + exn)
        }
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
    Await.result(db.run(q.update(startTime)), 30 seconds)
  }
  def setEndTime(id:Int) = {
    val date = new Date()
    val endTime = Some(new Timestamp(date.getTime))
    val q = for(
      j <- jobQry if j.jobId === id
    )yield j.ended
    Await.result(db.run(q.update(endTime)), 30 seconds)
  }
  def acquireJob(owner:String): Option[JobRow] = {
    //TODO: make sure this returns NONE if something else succeeds
    val q = for(
      j <- jobQry if j.status === "new"
    ) yield j
    val pendingJob = Await.result(
      db.run(q.take(1).result), 30 seconds
    )
    if(pendingJob.isEmpty){
      None
    }else{
      val row = pendingJob.head
      val updQ = jobQry.filter(j => j.jobId === row.jobId && j.status === "new")
          .map(v => (v.owner,v.status)).update(owner, "acquired")
      Await.result(db.run(updQ.transactionally).map { res =>
        Some(row)
      }.recover {
        case _: java.sql.SQLException => None
      }, 30 seconds)
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
  case class ResultDir(jobId:Int, f:File, jobTag:Option[String]){
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
        DBResult(id = 0, jobid = jobId,qry = write(rs.q), loc = write(rs.loc), result = write[ResultSummary](rs.resultSummary), queryTime = rs.time
          ,resultData = resDataId, apkHash = apkHash,
          bounderJarHash = bounderJarHash, owner = BounderUtil.systemID(), jobTag = jobTag)
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
    val rand = SimpleFunction.nullary[Double]("rand")
    val getJobs = for {
      job <- jobQry
      res <- resultsQry if job.jobId === res.jobId && res.result === filterResult
    } yield (job,res)
    val jobRows = Await.result(db.run(getJobs.result), 90 seconds)

    val limitJobRows = if(limit.isDefined) jobRows.take(limit.get) else jobRows

    val dataToDownload = limitJobRows.map{case (job, res) =>
      val cfg = read[RunConfig](job.config)
      val sOut:String = cfg.outFolder.get.replace("${baseDirOut}","").dropWhile(_ == "/")
      outFolder.createDirectoryIfNotExists(true)
      val currentOut = File(outFolder.toString + "/" +  sOut)
      println("out folder: " + currentOut)
      currentOut.createDirectoryIfNotExists(true)

      val resDir = currentOut / s"res_${res.id}"
//      (resDir / "id").append(res.id.toString)
      resDir.createIfNotExists(true)
      getCCParams(res).foreach{case (k,v) => (resDir / s"res_$k").append(v.toString)}
      getCCParams(job).foreach{case (k,v) => (resDir / s"job_$k").append(v.toString)}

      (res.resultData, currentOut)
    }.toSet

    dataToDownload.foreach{
      case (Some(d), out) =>
        println(s"downloading data $d to directory $out")
        val dataDir = out / s"data_$d"
        dataDir.createIfNotExists(true)
        val data = (dataDir / "data.zip")
        getData(d, data)
    }
  }
  def finishSuccess(d : ResultDir, stdout:String, stderr:String) = {
    val resData = d.getDBResults
    resData.foreach{d =>
      Await.result(db.run(resultsQry += d), 30 seconds)
    }
    val q = for(
      j <- jobQry if j.jobId === d.jobId
    ) yield (j.status, j.stdout, j.stderr)
    Await.result(db.run(q.update(("completed",Some(stdout),Some(stderr)))), 30 seconds)
  }
  def finishFail(id:Int, exn:String) = {
    val q = for(
      j <- jobQry if j.jobId === id
    ) yield j.status
    Await.result(db.run(q.update("failed: " + exn)), 30 seconds)
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
      30 seconds
    )
  }
  def downloadApk(name:String, outFile:File) :Boolean= {
    val qry = for {
      row <- apkQry if row.name === name
    } yield row.img
    val bytes: Seq[Array[Byte]] = Await.result(
      db.run(qry.take(1).result), 60 seconds
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
    Await.result(db.run(insertQuery += runInputs), 30 seconds)
  }
  def getInputs(id:Int, bounderJar:File, specFile:File) = {
    val q1 = for {
      inp <- inputsQuery if inp.id === id
    } yield (inp.bounderjar,inp.specfile)
    val inputFiles = Await.result(db.run(q1.result), 30 seconds)
    assert(inputFiles.size == 1)
    val (bounderJarHash, specFileHash) = inputFiles.head
    downloadApk("jar_" + bounderJarHash, bounderJar)
    downloadApk("spec_" + specFileHash, specFile)
  }
}
