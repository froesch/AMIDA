package amidar;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

import org.apache.bcel.classfile.ClassFormatException;
import org.apache.bcel.generic.TargetLostException;

import cgramodel.CgraModel;

import com.sun.media.jfxmedia.events.NewFrameEvent;
import com.sun.org.apache.xerces.internal.impl.dv.xs.DecimalDV;

import scheduler.RCListSched.AliasingSpeculation;
import tracer.CheckWriter;
import tracer.Trace;
import tracer.TraceManager;
import converter.Converter;
import converter.classloader.exceptions.ClassNotInClassPathException;
import converter.classloader.exceptions.FileNotInJarException;
import converter.classloader.exceptions.NoCorrectCPIndexException;
import converter.classloader.exceptions.ReadInException;
import converter.classloader.exceptions.StatisticsException;
import converter.classloader.exceptions.UnvalidConstantPoolTagException;
import converter.classloader.exceptions.Utf8EntryException;
import converter.exceptions.ByteCodeResolveException;
import converter.exceptions.NotAByteCodeException;
import converter.exceptions.ParsingByteCodeException;
import converter.parameter.AxtParameter;
import exceptions.AmidarSimulatorException;
import functionalunit.CGRA;
import generator.RedundancyChecker;
import generator.Stimulus;
import generator.StimulusAmidar;
import generator.TestbenchContextGenerator;
import generator.TestbenchExecutor;
import generator.TestbenchGeneratorAmidar;
import generator.VerilogGenerator;
import generator.VerilogGeneratorAmidar;
import graph.DCFG;
import amidar.axtLoader.AXTLoader;
import amidar.axtLoader.DebugInfoLoader;
import amidar.sweep.AmidarRemoteManager;
import amidar.sweep.ClientSimulatorTask;
import amidar.sweep.RemoteManager;
import amidar.sweep.SweepConfig;


public class AmidarSimulator{

	/**
	 * Starts the simulator. When it's called without arguments the run options are displayed
	 * @param args the run options
	 */
	public static void main(String[] args) {
		if(args == null || args.length == 0){
			printRunOptions();
			return;
		}


		switch(args[0]){
		case "-testCGRAVerilog":
		case "-simple":
			simpleRun(args);
			break;
		case "-simpleSpeedup":
			simpleSpeedupRun(args);
			break;
		case "-sweep":
			localParallelSweep(args);
			break;
		case "-sweepRemote":
			parallelSweep(args);
			break;
		case "-synthesize":
			standaloneSynthesize(args);
			break;
		case "-test":
			test(args);
			break;
		case "-speedup":
			speedup(args);
			break;
		default:
			System.out.println("Wrong run options");
			printRunOptions();
			return;
		}


	}

	/**
	 * Prints the run Options on the console
	 */
	private static void printRunOptions(){
		System.out.println("Run options:");
		System.out.println("  -simple           <configFile> <pathToApplication> <synthesis(true/false)>");
		System.out.println("  -simpleSpeedup    <configFile> <pathToApplication>");
		System.out.println("  -sweep            <configFile> <pathToApplication> <sweepConfig> <parallelism(integer)> <synthesis(true/false)>");
		System.out.println("  -sweepRemote      <configFile> <pathToApplication> <sweepConfig> <parallelism(integer)> <synthesis(true/false)> <hostAddress> <hostPort>");
		System.out.println("  -synthesize       <configFile> <pathToApplication> <methodName> <scheduleCDFG(true/false)>");
		System.out.println("  -test             <configFile> <pathToApplication>");
		System.out.println("  -speedup          <configFile> <pathToApplication>");
		System.out.println("  -testCGRAVerilog  <configFile> <pathToApplication>");
	}
	
	
	//-speedup config/amidar.json de/amidar/crypto/SkipjackTest_correctness

	/**
	 * Executes a single simulation with the given parameters
	 * @param configManager the configuration manager storing the whole configuration
	 * @param outputFile Denotes the File in which the output is written. If it is null, output is written to System.out
	 * @return returns an object containing all relevant metrics of the simulation
	 */
	public static AmidarSimulationResult run(ConfMan configManager, FileOutputStream outputFile, boolean saveCore){
		String applicationPath = configManager.getApplicationPath();
		TraceManager traceManager;
		if(outputFile == null){
			traceManager = new TraceManager();
		} else {
			traceManager = new TraceManager(outputFile);
		}
		configManager.configureTraceManager(traceManager);
		
		Amidar amidarCore = new Amidar(configManager, traceManager);
		AXTLoader axtLoader = new AXTLoader(applicationPath);
		amidarCore.setApplication(axtLoader);

		AmidarSimulationResult results = amidarCore.simulate(saveCore);
		
		return results;
	}




	/**
	 * Starts a single run. The arguments have to be:
	 * <ul>
	 * <li><b>args[1]</b>: path to configuration file</li>
	 * <li><b>args[2]</b>: path to application file</li>
	 * <li><b>args[3]</b>: boolean which decides whether synthesis is switched on or off</li>
	 * </ul>
	 * @param args the arguments
	 */
	private static void simpleRun(String [] args){
		String applicationPath = convertApplication(args[2], null);
		boolean testHardware = args[0].equals("-testCGRAVerilog");
		
		
		boolean synthesis = false;
		if(args[0].equals("-testCGRAVerilog") || args[3].equals("true")){
			synthesis = true;
		} else if (args[3].equals("false")){
			synthesis = false;
		} else{
			System.out.println("Synthesis parameter not set correctly: " + args[3]+ "");
			System.out.println("Valid values are \"true\" and \"false\"");
			System.out.println("Aborting...");
			return;
		}
		
		
		ConfMan configManager = new ConfMan(args[1], applicationPath, synthesis);
		
		Trace simpleRunTrace = new Trace(System.out, System.in, "", "");
		if(configManager.getTraceActivation("config")){
			simpleRunTrace.setPrefix("config");
			configManager.printConfig(simpleRunTrace);
		}

		DecimalFormatSymbols symbols = new DecimalFormatSymbols();
		symbols.setGroupingSeparator(',');
		symbols.setDecimalSeparator('.');
		DecimalFormat formater = new DecimalFormat("#.000", symbols);
		
		AmidarSimulationResult results = run(configManager, null, testHardware);
		
		if(configManager.getTraceActivation("results")){
			simpleRunTrace.setPrefix("results");
			simpleRunTrace.printTableHeader("Simulated "+applicationPath+" - Synthesis "+(configManager.getSynthesis()?"ON":"OFF"));
			simpleRunTrace.println("Ticks:               "+results.getTicks());
			simpleRunTrace.println("Bytecodes:           "+results.getByteCodes());
			simpleRunTrace.println("Energy consumption:  "+formater.format(results.getEnergy()));
			simpleRunTrace.println("Execution Time:      "+results.getExecutionDuration()+" ms");
			simpleRunTrace.printTableHeader("Loop Profiling");
			results.getProfiler().reportProfile(simpleRunTrace);
		}
		
		if(testHardware){
			simpleRunTrace.setPrefix("CGRA verilog test");
			simpleRunTrace.printTableHeader("Testing CGRA Verilog descrption");
			Amidar core = results.getAmidarCore();
			CGRA myCGRA =  (CGRA)core.functionalUnits[core.functionalUnits.length-1]; // CGRA is the last one
			
		    VerilogGenerator gen = target.Processor.Instance.getGenerator();
		    CgraModel model = myCGRA.getModel();
		    model.finalize();
		    simpleRunTrace.println("Generate Verilog...");
		    gen.printVerilogDescription("out",model);
		    TestbenchGeneratorAmidar tbgen = new TestbenchGeneratorAmidar((VerilogGeneratorAmidar) gen);
		    StimulusAmidar[] stimuli = new StimulusAmidar[1];
		    stimuli = myCGRA.getStimulus().toArray(stimuli);
		    TestbenchContextGenerator tbcongen = new TestbenchContextGenerator(model);
		    tbcongen.exportContext(myCGRA.getContextCopyPEs(), myCGRA.getContextCopyCBOX(), myCGRA.getContextCopyCCU());
//		    for(Stimulus stim : stimuli){
//		    	System.out.println(stim);
//		    }
		    
		    tbgen.exportAppAndPrintTestbench(stimuli);
		    
//		    tbgen.importAppAndPrintTestbench("SimpleTest.main");
		    TestbenchExecutor tbex = new TestbenchExecutor();
		    
		    if(tbex.runTestbench()){
		    	simpleRunTrace.println("Run was successful - Cosimulation succeeded");
		    }
		    else{
		    	RedundancyChecker sammi = new RedundancyChecker();
		    	sammi.findRegfileMissmatch();
		    	simpleRunTrace.println("Error(s) during Simulation");
		    }
		}
		

	}

	/**
	 * Starts two runs - one with and one without synthesis in order to
	 * measure the speedup.
	 * The arguments have to be:
	 * <ul>
	 * <li><b>args[1]</b>: path to configuration file</li>
	 * <li><b>args[2]</b>: path to application file</li>
	 * </ul>
	 * @param args the arguments
	 * @return the speedup
	 */
	private static double simpleSpeedupRun(String [] args){
		String applicationPath = convertApplication(args[2], null);
		ConfMan configManager;

		configManager = new ConfMan(args[1],applicationPath, false);
		
		Trace speedupTrace = new Trace(System.out, System.in, "", "");
		if(configManager.getTraceActivation("config")){
			speedupTrace.setPrefix("config");
			configManager.printConfig(speedupTrace);
		}
		speedupTrace.setPrefix("speedup");
		
		
		speedupTrace.printTableHeader("Measuring simple speedup");
		speedupTrace.printTableHeader("Running without Synthesis");
		AmidarSimulationResult resultsOFF = run(configManager, null, false);

		
		
		speedupTrace.printTableHeader("Running again with Synthesis");
		configManager.setSynthesis(true);
		AmidarSimulationResult resultsON = run(configManager, null, false);

		double speedup = (double)resultsOFF.getTicks()/(double)resultsON.getTicks();
		double energySavings = (double)resultsON.getEnergy()/(double)resultsOFF.getEnergy();

		DecimalFormatSymbols symbols = new DecimalFormatSymbols();
		symbols.setGroupingSeparator(',');
		symbols.setDecimalSeparator('.');
		DecimalFormat formater = new DecimalFormat("#0.000", symbols);

		if(configManager.getTraceActivation("results")){
			speedupTrace.setPrefix("results");
			speedupTrace.printTableHeader("Simulated "+applicationPath+" - Simple Speedup Measurement");
			speedupTrace.println("Ticks without synthesis:       "+resultsOFF.getTicks());
			speedupTrace.println("Ticks with synthesis:          "+resultsON.getTicks());
			speedupTrace.println("Speedup:                       "+formater.format(speedup));
			speedupTrace.println();
			speedupTrace.println("Energy without synthesis:      "+formater.format(resultsOFF.getEnergy()));
			speedupTrace.println("Energy with synthesis:         "+formater.format(resultsON.getEnergy()));
			speedupTrace.println("Energy savings:                "+formater.format((1-energySavings)*100) + "%");
			
			speedupTrace.printTableHeader("Loop Profiling");
			resultsON.getProfiler().reportProfile(speedupTrace);
		}
			
		return speedup;

	}

	
	/**
	 * Starts a sweep. The arguments have to be:
	 * <ul>
	 * <li><b>args[1]</b>: Path to basic configuration file</li>
	 * <li><b>args[2]</b>: Path to application file</li>
	 * <li><b>args[3]</b>: Path to sweep configuration file</li>
	 * <li><b>args[4]</b>: The number of parallel simulation Instances</li>
	 * <li><b>args[5]</b>: Boolean which decides whether synthesis is switched on or off</li>
	 * </ul>
	 * Starts Amidar remote simulators and a RMI registry locally as seperate processes. The remote simulators are registered as stubs.
	 * The corresponding number of threads is invoked in the current thread to invoke simulations on the remote simulators. 
	 * @param args the arguments
	 */
	private static void localParallelSweep(String [] args){
		String applicationPath = convertApplication(args[2], null);
		ConfMan baseConfigManager;
		

		boolean synthesis = false;
		if(args[5].equals("true")){
			synthesis = true;
		}
		else if (args[5].equals("false")){
			synthesis = false;
		} else{
			System.out.println("Synthesis parameter not set correctly: " + args[3]+ "");
			System.out.println("Valid values are \"true\" and \"false\"");
			System.out.println("Aborting...");
			return;
		}



		baseConfigManager = new ConfMan(args[1], applicationPath, synthesis);
		ConfMan[] cms;
		AmidarSimulationResult[] results;
		
		SweepConfig sweepConfig = new SweepConfig(baseConfigManager, args[3],false);

		cms = sweepConfig.getConfManager();

		Trace sweepTrace = new Trace(System.out, System.in, "", "");
		sweepTrace.setPrefix("basic config");
		baseConfigManager.printConfig(sweepTrace);

		sweepTrace.setPrefix("sweep");
		sweepTrace.printTableHeader("Sweeping:");




		//////// Starting RMI Servers + Creating client Threads /////////////////////////
		long start = 0;
		long stop = 0;
		Process regProcess = null;
		Process[] servers = null;
		String workingDirectory = System.getProperty("user.dir");
		workingDirectory = workingDirectory.substring(0, workingDirectory.length()-7);
		String codeBase = 	"file://"+workingDirectory+"/AmidarTools/bin/ "+
							"file://"+workingDirectory+"/Synthesis/bin/ "+
							"file://"+workingDirectory+"/cgra/CGRA/bin/ "+
							"file://"+workingDirectory+"/Amidar/bin/ "+
							"file://"+workingDirectory+"/AXTLoader/bin/ "+
							"file://"+workingDirectory+"/AmidarTools/lib/axtConverter.jar "+
							"file://"+workingDirectory+"/AmidarTools/lib/bcel-5.2.jar "+
							"file://"+workingDirectory+"/AmidarTools/lib/commons-lang-2.6.jar "+
							"file://"+workingDirectory+"/AmidarTools/lib/j-text-utils-0.3.3.jar "+
							"file://"+workingDirectory+"/AmidarTools/lib/json-simple-1.1.1.jar "+
							"file://"+workingDirectory+"/AmidarTools/lib/lombok.jar";
							
		ProcessBuilder regProcessBuilder = new ProcessBuilder("/usr/lib/jvm/java-8-oracle/bin/rmiregistry", "-J-Djava.rmi.server.codebase=" + codeBase);
		try {
			File logFolder = new File("log/remoteSimLog");
			logFolder.mkdirs();
			
			sweepTrace.println("Starting RMI registry...");
			File regLog = new File("log/remoteSimLog/registry.log");
			regProcessBuilder.redirectError(regLog);
			regProcessBuilder.redirectOutput(regLog);
			regProcess = regProcessBuilder.start();
			sweepTrace.println("  DONE");

			int nrOfServers = Integer.parseInt(args[4]);
			servers = new Process[nrOfServers];
			ClientSimulatorTask[] clientThreads = new ClientSimulatorTask[nrOfServers];
			
			Thread.sleep(500);
			
			for(int i = 0; i < nrOfServers; i++){
				String serverName = AmidarRemoteManager.REMOTE_SERVER_STUB_NAME + i ;
				
				sweepTrace.println("Starting remote simulator " + serverName);
				ProcessBuilder pb = new ProcessBuilder("java", "-cp", "../Amidar/bin:../AmidarTools/bin:../AmidarTools/lib/axtConverter.jar:../AmidarTools/lib/bcel-5.2.jar:../AmidarTools/lib/commons-lang-2.6.jar:../AmidarTools/lib/j-text-utils-0.3.3.jar:../AmidarTools/lib/json-simple-1.1.1.jar:../AmidarTools/lib/lombok.jar:../AXTLoader/bin:../Synthesis/bin:../cgra/CGRA/bin", "amidar.sweep.AmidarRemoteSimulator", serverName);
				File log = new  File("log/remoteSimLog/"+serverName+".log");
				pb.redirectError(log);
				pb.redirectOutput(log);

				servers[i] = pb.start();
				//				System.out.println(servers[i].waitFor());

				Trace clientTrace = new Trace(System.out, System.in, "", "");
				clientTrace.setPrefix("client "+ i);

				clientThreads[i] = new ClientSimulatorTask(serverName, clientTrace, "localhost", 1099);
				sweepTrace.println("  DONE");
			}
			////////////////////////////////////////////////////////
				Thread.sleep(500);

			ClientSimulatorTask.init(sweepConfig, "log/sweep");

			///
			start = System.nanoTime();
			for(int i = 0; i< nrOfServers; i++ ){
				sweepTrace.println("Starting client thread no"+i);
				clientThreads[i].start();
			}

			for(int i = 0; i< nrOfServers; i++ ){
				clientThreads[i].join();
				sweepTrace.println("Finished client thread no"+i);
			}
			stop = System.nanoTime();
			////

		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} finally {
			if(regProcess != null){
				regProcess.destroy();
				sweepTrace.println("Ended RMI registry");
			}
			if(servers != null){
				for(int i = 0; i< servers.length; i++){
					if(servers[i] != null){
						servers[i].destroy();
						sweepTrace.println("Ended remote simulator Amidar_"+i);
					}
				}
			}
		}



		
		results = ClientSimulatorTask.getResults();
		
		if(sweepTrace.active()){
			sweepTrace.setPrefix("results");
			sweepTrace.printTableHeader("Simulated "+applicationPath+" - Synthesis "+(synthesis?"ON":"OFF"));
			
			String[] configs = sweepConfig.getSweepConfigurations();
			for(int i = 0; i<cms.length; i++){
				sweepTrace.println(configs[i] + ":");
				sweepTrace.println("\tTicks:               "+results[i].getTicks());
				sweepTrace.println("\tBytecodes:           "+results[i].getByteCodes());
				
				//				resultTrace.printTableHeader("Loop Profiling");
				//				results.get(tm).getProfiler().reportProfile(resultTrace);
			}
			sweepTrace.println();
			sweepTrace.println("Execution Time:      "+(stop-start)/1000000+" ms");
		}
		
		exportPlot(results, sweepConfig);


	}
	
	
	/**
	 * Starts a sweep. The arguments have to be:
	 * <ul>
	 * <li><b>args[1]</b>: Path to basic configuration file</li>
	 * <li><b>args[2]</b>: Path to application file</li>
	 * <li><b>args[3]</b>: Path to sweep configuration file</li>
	 * <li><b>args[4]</b>: The number of parallel simulation Instances</li>
	 * <li><b>args[5]</b>: Boolean which decides whether synthesis is switched on or off</li>
	 * <li><b>args[6]</b>: Registry host address</li>
	 * <li><b>args[7]</b>: Registry host port</li>
	 * </ul>
	 * Starts Amidar remote simulators on a remote machine using RMI.
	 * The corresponding number of threads is invoked in the current thread to invoke simulations on the remote simulators. 
	 * @param args the arguments
	 */
	private static void parallelSweep(String [] args){
		
		/// CREATE configurations ///////////////////////////////////
		String applicationPath = convertApplication(args[2], null);
		ConfMan baseConfigManager;
		

		boolean synthesis = false;
		if(args[5].equals("true")){
			synthesis = true;
		}
		else if (args[5].equals("false")){
			synthesis = false;
		} else{
			System.out.println("Synthesis parameter not set correctly: " + args[3]+ "");
			System.out.println("Valid values are \"true\" and \"false\"");
			System.out.println("Aborting...");
			return;
		}



		baseConfigManager = new ConfMan(args[1], applicationPath, synthesis);
		ConfMan[] cms;
		AmidarSimulationResult[] results;
		
		SweepConfig sweepConfig = new SweepConfig(baseConfigManager, args[3],false);

		cms = sweepConfig.getConfManager();

		Trace sweepTrace = new Trace(System.out, System.in, "", "");
		sweepTrace.setPrefix("basic config");
		baseConfigManager.printConfig(sweepTrace);

		sweepTrace.setPrefix("sweep");
		sweepTrace.printTableHeader("Sweeping:");

		String registryHost = args[6];
		int registryPort = Integer.parseInt(args[7]);
		int nrOfServers = Integer.parseInt(args[4]);
		//////// Preparing remote /////////
		long stop, start = System.nanoTime();
		results = parallelRemoteSimulation(sweepConfig, registryHost, registryPort, nrOfServers, sweepTrace);
		stop = System.nanoTime();
		
		if(sweepTrace.active()){
			sweepTrace.setPrefix("results");
			sweepTrace.printTableHeader("Simulated "+applicationPath+" - Synthesis "+(synthesis?"ON":"OFF"));
			
			String[] configs = sweepConfig.getSweepConfigurations();
			for(int i = 0; i<cms.length; i++){
				sweepTrace.println(configs[i] + ":");
				sweepTrace.println("\tTicks:               "+results[i].getTicks());
				sweepTrace.println("\tBytecodes:           "+results[i].getByteCodes());
				
				//				resultTrace.printTableHeader("Loop Profiling");
				//				results.get(tm).getProfiler().reportProfile(resultTrace);
			}
			sweepTrace.println();
			sweepTrace.println("Execution Time:      "+(stop-start)/1000000+" ms");
		}
		exportPlot(results, sweepConfig);

	}
	
	private static AmidarSimulationResult[] parallelRemoteSimulation(SweepConfig sweepConfig, String registryHost, int registryPort, int nrOfServers, Trace sweepTrace){
		AmidarSimulationResult[] results = null;
		////////Preparing remote /////////
		
		RemoteManager manager = null;


		//////// Starting RMI Servers + Creating client Threads /////////////////////////
		try {
			Registry reg = LocateRegistry.getRegistry(registryHost, registryPort);
			manager = (RemoteManager) reg.lookup(AmidarRemoteManager.REMOTE_MANAGER_STUB_NAME);
			
			String[] apps = sweepConfig.getApplications();
			
			for(int i = 0; i < apps.length; i++){
				manager.convertApplication(apps[i], null);
			}
			
			manager.createServers(nrOfServers);

			ClientSimulatorTask[] clientThreads = new ClientSimulatorTask[nrOfServers];
			
			Thread.sleep(500);
			
			for(int i = 0; i < nrOfServers; i++){
				String serverName = AmidarRemoteManager.REMOTE_SERVER_STUB_NAME + i ;
				

				Trace clientTrace = new Trace(System.out, System.in, "", "");
				clientTrace.setPrefix("client "+ i);

				clientThreads[i] = new ClientSimulatorTask(serverName, clientTrace, registryHost, registryPort);
				sweepTrace.println("  DONE");
			}
			////////////////////////////////////////////////////////
				Thread.sleep(500);

			
			ClientSimulatorTask.init(sweepConfig, "log/sweep");

			///
			for(int i = 0; i< nrOfServers; i++ ){
				sweepTrace.println("Starting client thread no"+i);
				clientThreads[i].start();
			}

			for(int i = 0; i< nrOfServers; i++ ){
				clientThreads[i].join();
				sweepTrace.println("Finished client thread no"+i);
			}

		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} finally {
			try {
				manager.closeServers();
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		}



		
		results = ClientSimulatorTask.getResults();
		
		return results;
	}
	
	
	private static void test(String [] args){
		String applicationPath = convertApplication(args[2], null);
		ConfMan baseConfigManager;
		




		baseConfigManager = new ConfMan(args[1], applicationPath, false);
		ConfMan[] cms;
		
		SweepConfig sweepConfig = new SweepConfig(baseConfigManager, "config/sweep/testSweep.json",false);

		cms = sweepConfig.getConfManager();
		String[] configNames = sweepConfig.getSweepConfigurations();

		Trace testTrace = new Trace(System.out, System.in, "", "");
		testTrace.setPrefix("basic config");
		baseConfigManager.printConfig(testTrace);

		testTrace.setPrefix("test");
		
		int errorCnt = 0;
		int warningCnt = 0;

//		for(ConfMan conf: cms){
		for(int i = 0; i<cms.length; i++){
			ConfMan conf = cms[i];
			
			CheckWriter cw = conf.activateOutputCheck();
			
			
			/// CREATE reference output from execution on JVM /////////////////////////////////
			
			String javaHome = System.getProperty("java.home");
			String javaBin = javaHome +
					File.separator + "bin" +
					File.separator + "java";
			String classpath = "../Applications/bin:../API/bin";
			classpath = classpath.replace("\\", File.separator);

			
			String application = conf.getApplicationPath();
			String applicationClass = application.replaceAll("../axt/", "");
			applicationClass = applicationClass.replaceAll("/[^/]+.axt", "");
			applicationClass = applicationClass.replace('/','.');
			ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", classpath, applicationClass);
			
			String result = "ERROR while executing on JVM";
			
			Process process;
			try {
				process = pb.start();
				process.waitFor();
				

				BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
				StringBuilder builder = new StringBuilder();
				String line = null;
				while ( (line = reader.readLine()) != null) {
					builder.append(line);
					builder.append(System.getProperty("line.separator"));
				}
				result = builder.toString();
				
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			////////////////////////////////////////////////////////////////////////////////////
			
			
			
			testTrace.printTableHeader("Testing: " + configNames[i]);
			testTrace.println("Running without synthesis...");
			run(conf, null, false);
			
			
			if(!cw.check(result)){
				errorCnt++;
				testTrace.println("\tFound Error. Expected: \t" + cw.getExpected());
				testTrace.println("\t\tOutput was: " + cw.getOutput());
			} else {
				testTrace.println("\tpassed");
			}
			
			conf.setSynthesis(true);
			testTrace.println("Running with synthesis...");
			try{
				run(conf, null, false);


				if(!cw.check(result)){
					errorCnt++;
					testTrace.println("\tFound Error. Expected: \t" + cw.getExpected());
					testTrace.println("\t\tOutput was: " + cw.getOutput());
				} else {
					testTrace.println("\tpassed");
				}
			} catch (AmidarSimulatorException ex){
				testTrace.println("WARNING: Aliasing speculation failed. Switching of speculation and repeat...");
				warningCnt++;
				conf.getSynthesisConfig().put("ALIASING_SPECULATION", AliasingSpeculation.OFF);
				run(conf, null, false);


				if(!cw.check(result)){
					errorCnt++;
					testTrace.println("\tFound Error. Expected: \t" + cw.getExpected());
					testTrace.println("\t\tOutput was: " + cw.getOutput());
				} else {
					testTrace.println("\tpassed");
				}
				
//				errorCnt++;
//				testTrace.println("\tError: Could not Simulate:");
//				testTrace.println(ex.getMessage());
			}
			
			
			
		}
		
		testTrace.printTableHeader("Final Results");
		if(warningCnt > 1){
			testTrace.println(warningCnt + " Warnings");
		} else if(warningCnt == 1){
			testTrace.println("1 Warning");
		}
		if(errorCnt > 1){
			testTrace.println("TEST FAILED with " + errorCnt + " Errors");
		} else if(errorCnt == 1){
			testTrace.println("TEST FAILED with 1 Error");
		} else if(errorCnt == 0){
			testTrace.println("TEST PASSED");
		}
			
		
		


	
	}
	
	/**
	 * Measures the speedup for all applications exactly
	 * @param args
	 */
	private static void speedup(String [] args){
		
		int warningCnt = 0;
		
		String applicationPath = convertApplication(args[2], null);
		ConfMan baseConfigManager;
		




		baseConfigManager = new ConfMan(args[1], applicationPath, false);
		ConfMan[] cms;
		
		SweepConfig sweepConfig = new SweepConfig(baseConfigManager, "config/sweep/speedupSweep.json",true);

		cms = sweepConfig.getConfManager();
		String[] configNames = sweepConfig.getSweepConfigurations();

		Trace speedupTrace = new Trace(System.out, System.in, "", "");
		speedupTrace.setPrefix("basic config");
		baseConfigManager.printConfig(speedupTrace);

		speedupTrace.setPrefix("speedup");
		
		LinkedHashMap<String, SpeedupMeasurementResult> speedupResults = new LinkedHashMap<>();
		
//		AmidarSimulationResult[] results = parallelRemoteSimulation(sweepConfig, "trav", 1099, 8, speedupTrace);
		

		for(int i = 0; i<cms.length; i++){
			ConfMan conf = cms[i];
			
			boolean isShort;
			String app = conf.getApplicationPath();
			int end = app.lastIndexOf('/');
			app = app.substring(0, end);
			if(app.endsWith("_long")){
				app = app.substring(0, app.length()-5);
				isShort = false;
			} else {
				app = app.substring(0, app.length()-6);
				isShort = true;
			}
			
			
			SpeedupMeasurementResult speedupRes = speedupResults.get(app);
			if(speedupRes == null){
				speedupRes = new SpeedupMeasurementResult();
				speedupResults.put(app, speedupRes);
			}
			
			speedupTrace.printTableHeader("Running: " + configNames[i]);
			if(!speedupResults.get(app).isBaseLineAvaliable(isShort)){
				speedupTrace.println("Running without synthesis...");
				AmidarSimulationResult currentRes = run(conf, null, false);
				speedupRes.addBaseline(currentRes, isShort);
			}
			
			
			AmidarSimulationResult currentResult = null;
			conf.setSynthesis(true);
			speedupTrace.println("Running with synthesis...");
			try{
				currentResult = run(conf, null, false);
			} catch(AmidarSimulatorException e ){
//				speedupTrace.println("ERROR: " + e.getMessage());
				speedupTrace.println("WARNING: Aliasing speculation failed. Switching of speculation and repeat...");
				warningCnt++;
				conf.getSynthesisConfig().put("ALIASING_SPECULATION", AliasingSpeculation.OFF);
				currentResult = run(conf, null, false);
				
			}
//			currentResult = results[i];
			
			speedupRes.addTicks(currentResult, ((Long)conf.getSynthesisConfig().get("UNROLL")).intValue(), isShort);
		}

		speedupTrace.printTableHeader("Speedup");
		
		DecimalFormatSymbols symbols = new DecimalFormatSymbols();
		symbols.setGroupingSeparator(',');
		symbols.setDecimalSeparator('.');
		DecimalFormat formater = new DecimalFormat("#0.000", symbols);
		
		
		LinkedHashMap<Integer, Double> speedups = new LinkedHashMap<>();
		for(Object u: sweepConfig.getSweeps().get("UNROLL")){
			int unroll = ((Long)u).intValue();
			speedups.put(unroll, 0.0);
		}
		
		
		for(String app: speedupResults.keySet()){
//			speedupTrace.println("Speedup of " + app);
			String res = "Speedup of " + app + "\t";
			for(Integer unroll: speedups.keySet()){
				double speedup = speedupResults.get(app).getSpeedup(unroll);
				speedups.put(unroll, speedups.get(unroll) + speedup);
				res = res + "\twith UNROLL = " + unroll + ": " + formater.format(speedup);
			}
			speedupTrace.println(res);
		}
			
		speedupTrace.printTableHeader("Average Speedup");
		int nrOfApps = speedupResults.keySet().size();
		
		
		if(warningCnt > 1){
			speedupTrace.println(warningCnt + " Warnings");
		} else if(warningCnt == 1){
			speedupTrace.println("1 Warning");
		}
		for(Integer unroll: speedups.keySet()){
			speedupTrace.println("UNROLL " + unroll + ": " + formater.format(speedups.get(unroll)/nrOfApps));
		}
		
		
		
		


	
	}
	
	
	/**
	 * Starts synthesis without simulation. The arguments have to be:
	 * <ul>
	 * <li><b>args[1]</b>: path to configuration file</li>
	 * <li><b>args[2]</b>: path to application file</li>
	 * <li><b>args[3]</b>: Identifier of method in which synthesizable code is searched</li>
	 * <li><b>args[4]</b>: boolean which decides whether the found CDFG should also be scheduled</li>
	 * </ul>
	 * @param args the arguments
	 */
	public static void standaloneSynthesize(String [] args){
		String applicationPath = convertApplication(args[2], null);
		
		boolean schedule = false;
		if(args[4].equals("true")){
			schedule = true;
		} else if (args[4].equals("false")){
			schedule = false;
		} else{//TODO
			System.out.println("Synthesis parameter not set correctly: " + args[3]+ "");
			System.out.println("Valid values are \"true\" and \"false\"");
			System.out.println("Aborting...");
			return;
		}
		
		
		ConfMan configManager = new ConfMan(args[1], applicationPath, true);
		
		Trace simpleRunTrace = new Trace(System.out, System.in, "", "");
		if(configManager.getTraceActivation("config")){
			simpleRunTrace.setPrefix("config");
			configManager.printConfig(simpleRunTrace);
		}

		TraceManager traceManager = new TraceManager();
		configManager.configureTraceManager(traceManager);

		Amidar amidarCore = new Amidar(configManager, traceManager);
		AXTLoader axtLoader = new AXTLoader(applicationPath);
		amidarCore.setApplication(axtLoader);

		amidarCore.synthesize(args[2] + "." +args[3], schedule);
		 
		
		
		
		
		
		if(configManager.getTraceActivation("results")){
			simpleRunTrace.setPrefix("results");
			simpleRunTrace.printTableHeader("Ran synthesis for kernels found in "+args[2] + "." +args[3]);
		}
		

	}
	
	


	/**
	 * Converts the given application into AXT-format. When an AXT-file already exists,
	 * the application is only converted again when the classfile is newer than the AXT-file.
	 * Application has to be located in ../Application/src/apps/ (TODO?)
	 * @param applicationPath the path to the application (e.g. "de/amidar/T02")
	 * @return the path to the axt file (e.g. "../axt/de/amidar/T02/T02.axt")
	 */
	public static String convertApplication(String applicationPath, String[] args){

		String [] pathSplitted = applicationPath.split("/");

		String applicationName = pathSplitted[pathSplitted.length-1];

		String path = "../axt/"+applicationPath+"/";

		File file = new File(path);
		file.mkdirs();

		String result = path+applicationName+".axt";

		File axt = new File(result);
		long lastModifiedAxt = axt.lastModified();
		File apps = new File("../Applications/bin/timeStamp"); // This file is touched every time the applications are built
		long lastAppBuild = apps.lastModified();
		File api = new File("../API/bin/timeStamp"); // This file is touched every time the API are built
		long lastApiBuild = api.lastModified();

		if(lastModifiedAxt < lastAppBuild || lastModifiedAxt < lastApiBuild){

			AxtParameter axtPara = new AxtParameter();
			axtPara.setClassPaths(new String [] {"../Applications/bin/", "../API/bin/"}); // This is the correct one but does not yet work
			if(args == null){
				axtPara.setMainArguments(new String [] {});
			} else {
				axtPara.setMainArguments(args);
			}
			axtPara.setMainClass(applicationPath);
			axtPara.setTargetName(applicationName);
			axtPara.setTargetPath(path);

			try {
				Converter converter = new Converter(axtPara);
				converter.convert();
			} catch (Utf8EntryException | UnvalidConstantPoolTagException
					| NotAByteCodeException | ClassNotInClassPathException
					| ReadInException | IOException | StatisticsException | IllegalArgumentException | IllegalAccessException | ClassFormatException | ParsingByteCodeException | ByteCodeResolveException | NoCorrectCPIndexException | TargetLostException | FileNotInJarException e) {
				e.printStackTrace(System.err);
				throw new AmidarSimulatorException("Error when converting application to AXT format");
			}
		}

		return result;
	}
	
	private static void exportPlot(AmidarSimulationResult[] results, SweepConfig sweepConfig){
		
		int dimensions = sweepConfig.getSweeps().size();
		
		int[] dimsizes = new int[dimensions];
		int cnt = 0;
		for(Set<Object> d : sweepConfig.getSweeps().values()){
			
			dimsizes[cnt++] = d.size();
		}
		
		int entriesPer2Dcsv = dimsizes[dimensions-1]*dimsizes[dimensions-2];
		int nrCSVs = results.length/entriesPer2Dcsv;
		
		Object[][] instanceConfigurations = sweepConfig.getInstanceConfigurations();
		
		int instanceCnt = 0;
		
		for(int csvNr = 0; csvNr < nrCSVs; csvNr++){
			Object[] config = instanceConfigurations[instanceCnt];
//			instanceCnt+=entriesPer2Dcsv;
			
			StringBuilder fileName = new StringBuilder(config[0].toString());  
			for(int i = 1; i < dimensions -2; i++){
				fileName.append("_"+ config[i].toString());
			}

			
			String fileNameString = fileName.toString().replaceAll("/", "_").replace(".._", "")+".csv";

			FileWriter fw;
			try {
				fw = new FileWriter("log/"+fileNameString);
				BufferedWriter bw = new BufferedWriter(fw);
			
				Iterator<String> it = sweepConfig.getSweeps().keySet().iterator();
				for(int i = 0; i < dimensions-2; i++){
					it.next();
				}
				
				bw.write("# Row : "+it.next()+"\n");
				bw.write("# Col : "+it.next()+"\n\n");
				
				for(int i = 0; i < dimsizes[dimensions-2]; i++){
					for(int j = 0; j < dimsizes[dimensions-1]-1; j++){
						bw.write(results[instanceCnt++].getTicks()+",");
					}
					bw.write(results[instanceCnt++].getTicks()+"\n");
				}
				bw.close();
				fw.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				System.err.println(e.getMessage());
				e.printStackTrace();
			}
			
			
			
			
			
		}
		
		
		
	}

}
