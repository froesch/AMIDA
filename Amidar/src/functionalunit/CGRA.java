package functionalunit;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;

import cgramodel.CgraModel;
import cgramodel.ContextMask;
import cgramodel.ContextMaskPE;
import tracer.TraceManager;
import exceptions.AmidarSimulatorException;
import functionalunit.cache.Cache;
import functionalunit.cache.Memory;
import functionalunit.cgra.Cbox;
import functionalunit.cgra.ContextMem;
import functionalunit.cgra.ControlUnit;
import functionalunit.cgra.HandleCompare;
import functionalunit.cgra.PE;
import functionalunit.opcodes.CgraOpcodes;
import generator.CgraInstruction;
import generator.StimulusAmidar;
import io.AttributeParser;
import target.Processor;

/**
 * This class is the frontend module for the CGRA. It emulates the toplevel module on a fined grained level. All
 * submodule emulation are triggered during simulation automatically. *  In order to trigger * the cycle based 
 * processing use the method operate().   
 * @author Dennis Wolf
 *
 */
public class CGRA extends FunctionalUnit<CgraOpcodes>  {

	/**
	 * The maximal number of PEs whose register file can be initialized via Token (see Token generation)
	 */
//	public static final int MAX_PES = 25;
//	public static final int MAX_REGFILE_ADDR_WIDTH = 32 - MAX_PES;
//	public static final int MUX_WIDTH = 5;
//	public static final int VIA_WIDTH = 4;
	

	
	/** 
	 * New model of the cgra, which is to be inserted.
	 */
	CgraModel model;

	
	public CgraModel getModel() {
		return model;
	}

	
	public void setCgraModel (CgraModel model) {
		this.model = model;
	}

	
	/**
	 * Array of all PEs in the CGRA
	 */
	public PE[] PEs;

	
	/**
	 * List of all PEs, which live out Connections 
	 */
	private int[] PEsliveOut;


	/**
	 * Array of all context-memories. Each PE is connected and and controled to one context-memory.
	 */
	public ContextMem[] context;


	/**
	 * Same information as Interconnect, but saved as a Set for the Scheduler   [in] -> LinkedList of all sources
	 */	
	public LinkedList<Integer>[] interconnectSet;

	/**
	 *  A magical box called "C"
	 */
	Cbox cbox;

	
	/**
	 * Context for the magical Box called "C"
	 */
	public ContextMem contextcbox;


	/**
	 * Control Unit manages the programm counter and stalls during a busy phase, when data is processed. 
	 */
	public ControlUnit controlunit;


	/**
	 * Valid inputs from the caches
	 */
	public boolean[] InputCacheValid;


	/**
	 * Valid output to the caches
	 */
	public boolean[] OutputCacheValid;

	
	/**
	 * Valid output to the caches
	 */
	public boolean[] OutputCacheWrite;

	
	/**
	 * Outputs holding the base and offset addresses. One each per PE that has cache access 
	 */
	public int[] cacheBaseAddress, cacheOffset;


	/**
	 * Data input/output from caches. One each per PE that has cache access 
	 */
	public int[] InputCacheData, OutputCacheData;


	/**
	 * Control signal to enable PEs
	 */
	private boolean enableSubmodules;

	
	/**
	 * Control signal to enable the control unit which generates the pc.
	 */
	public boolean CtrlEnable;

	
	/**
	 * Controlsignal to initial a write to all contexts. needs to be set in combination with 
	 */
	public boolean contextWrite;

	
	/**
	 * signal of joint cache enables
	 */
	public boolean BundledCacheEnable;

	
	/**
	 * helper variables or information
	 */
	public int pc,contextwidth,Cacheaddr,CacheOffSet, stateSizeControlUnit,cboxslots, contextsize,pboxcontextwidth,regsizeMax,pcwidth;

	
	/**
	 * Current state of the global FSM
	 */
	private CGRAState state = CGRAState.IDLE;
	
	
	/**
	 * Next state of the global FSM
	 */
	private CGRAState nextState = CGRAState.IDLE;

	ArrayList<CgraOpcodes> trackerOpcode =  new ArrayList<CgraOpcodes>();
	ArrayList<Integer> trackerOperandAddr =  new ArrayList<Integer>();
	ArrayList<Integer> trackerOperandData =  new ArrayList<Integer>();

	FileWriter fwRegDebug;
	FileWriter fwSystemDebug;
	
	ArrayList<StimulusAmidar> stimulus = new ArrayList<>();
//========== CACHE DEV ==========
	private Cache[] caches;
	private Memory mem;
	private int[] cacheTicks;

	
	public void createCaches(String configFile, TraceManager traceManager){
		for(int i = 0; i<caches.length; i++){
			caches[i] = new Cache(mem, configFile, i, true, traceManager);
		}
		for(int i = 0; i<caches.length; i++){
			caches[i].setMOESICaches(caches);
		}
	}
	
	public Cache[] getCaches(){
		return caches;
	}
	//========== /CACHE DEV ==========
	
	public HandleCompare handleCompare;

	/**
	 * Constructor of this class.  
	 */
	public CGRA(String configFile, boolean print, TraceManager traceManager, Memory mem){
		// TODO - OPcodes wechseln !
		super(CgraOpcodes.class, configFile, traceManager);
		this.mem = mem;
		createCaches(configFile, traceManager);
		input = new int[getNrOfInputports()];
		output =new int[1];
	}

	
	/**
	 * States of class CGRA 
	 */
	public static enum CGRAState{
		IDLE,
		WRITECONTEXT_RECEIVE,
		RECEIVELOCALVAR,
		//		WRITE,
		WRITECONTEXT_SEND,
		SENDLOCALVAR,
		SETADDRESS,
		RUN;
		//		ACK,
		//		SENDING;

		public static final int length = values().length;
	}

	ContextMask mask;

	protected void configureFU(String configFile){
		model = AttributeParser.loadCgra(configFile);
		model.finalize();
		mask = new ContextMask();
		mask.setContextWidth(32);

		//Actual Hardware components
		cbox = new Cbox();
		int nrOfPEs = model.getNrOfPEs();
		PEs = new PE[nrOfPEs];
		context = new ContextMem[nrOfPEs]; 


		//HashMap<Integer,String> elements = (HashMap<Integer,String>) json.get("PEs");
		for(int i = 0; i<nrOfPEs ; i++){
			PEs[i]=new PE();
			context[i] = new ContextMem();	
			context[i].configureContext(model.getContextMemorySize(), i);
			PEs[i].configure(model.getPEs().get(i),this);
			PEs[i].createPorts();
		}

		contextcbox = new ContextMem();
		contextcbox.configureContext(model.getContextMemorySize(), model.getNrOfPEs());
		int cachecounter = model.getNrOfMemoryAccessPEs();
		OutputCacheData = new int [cachecounter];
		InputCacheData = new int [cachecounter];
		InputCacheValid = new boolean [cachecounter];
		OutputCacheValid = new boolean [cachecounter];
		OutputCacheWrite = new boolean [cachecounter];
		cacheBaseAddress = new int [cachecounter];
		cacheOffset = new int [cachecounter];
		contextsize = model.getContextMemorySize();
		cacheTicks = new int[cachecounter];
		
		//========== CACHE DEV ==========
		caches = new Cache[cachecounter];
		//========= /CACHE DEV ==========

		int max=0;
		for(PE i : PEs){
			if(max<i.getNrOfInputports()){
				max = i.getNrOfInputports();
			}
		}
			
		cboxslots = model.getcBoxSlots();
		cbox.configure(model.getNrOfPEs(),model.getNrOfControlFlowPEs(), cboxslots,model.getContextmaskcbox());
		controlunit = new ControlUnit();
		stateSizeControlUnit = contextsize;
		pcwidth = controlunit.configure(contextsize,model.getContextmaskccu());
		int liveouts = 0;
		for(int i = 0; i <PEs.length;i++){
			if(PEs[i].getModel().getLiveout()){
				liveouts++;
			}
		}
		PEsliveOut = new int[liveouts];
		
		liveouts = 0;
		int cboxmappingcounter = 0;
		for(int i = 0; i <PEs.length;i++){
			if(PEs[i].getModel().getLiveout()){
				PEsliveOut[liveouts] = i;
				liveouts ++;
			}
			if(PEs[i].getModel().getControlFlow()){
				cbox.setInputMapping(cboxmappingcounter,i);
				cboxmappingcounter++;
			}
		}
		
		resetCGRA();
		
		handleCompare = new HandleCompare(model);
		
		
		super.configureFU(configFile);

		try {
			File folder = new File(Processor.Instance.getGenerator().getPathhelper().getDebuggingPath());
			if(!folder.exists()){
				folder.mkdir();
			}
		File debugregistesfile = new File(Processor.Instance.getGenerator().getPathhelper().getDebuggingPath()+"/debug_registerfiles_emulation");
		File debugsystemfile = new File(Processor.Instance.getGenerator().getPathhelper().getDebuggingPath()+"/debug_ALU_emulation");
		
			fwRegDebug = new FileWriter(debugregistesfile);
			fwSystemDebug = new FileWriter(debugsystemfile);
		} catch (IOException e) {
			System.err.println("opening filewriter in cgra didn't work");
		}
	}

	/**
	 * Returns Number of Inputports
	 */
	public int getNrOfInputports() {
		return 4;
	}

	/**
	 * Returns an Array of all PEs available in the CGRA  
	 */
	public PE[] getPEs(){
		return PEs;
	}

	/**
	 * Returns the ID of the PE 
	 */
	public PE getPeViaId(int id){
		return PEs[id];
	}

	/**
	 * Returns the Number of PEs
	 */
	public int getNumberOfPes(){
		return PEs.length;	
	}


	public LinkedList<Integer>[] getPeConnections(){
		return interconnectSet;
	}
	
	public ArrayList<StimulusAmidar> getStimulus(){
		int i = 0;
		for(i = 0; i < PEs.length; i++){
			stimulus.add(0, new StimulusAmidar(CgraInstruction.LOADPROGRAM, context[i].memory, i));
		}
		
		stimulus.add(0, new StimulusAmidar(CgraInstruction.LOADPROGRAM, controlunit.memory, i++));
		stimulus.add(0, new StimulusAmidar(CgraInstruction.LOADPROGRAM, contextcbox.memory, i));
		
		
		return stimulus;
	}
	
	public long[][] getContextCopyPEs(){
		long[][] contexts = new long[model.getNrOfPEs()][];
		for(int i = 0; i < PEs.length; i++){
			contexts[i] = context[i].memory;
		}
		return contexts;
	}
	
	public long[] getContextCopyCCU(){
		return controlunit.memory;
	}

	public long[] getContextCopyCBOX(){
		return contextcbox.memory;
	}
	
	/**
	 *	Copies a new Synthesis into the Context memory for all PEs with a magical Hand.
	 */
	public boolean newSynthesisContext(long[][] synthesis, int startingSlots ){
		for(int i = 0; i < PEs.length; i++){
			context[i].setContext(synthesis[i], startingSlots);
		}
		return true;
	}

	/**
	 * method to set the next state. emulates the behaviour of a real hardware
	 */
	private void nextState(CgraOpcodes op){

		switch(getState()){

		case IDLE:
			//			if(tokenValid && validInputs(InputOpcode)){
			switch(op){
			case RUN:
				nextState = CGRAState.SETADDRESS;
				break;
			case RECEIVELOCALVAR:
				nextState = CGRAState.WRITECONTEXT_RECEIVE;
				break;
			case SENDLOCALVAR:
				nextState = CGRAState.WRITECONTEXT_SEND;
				break;
			default : throw new AmidarSimulatorException("Unkown Opcode found in CGRA");
			}
			//			}
			break;

		case WRITECONTEXT_RECEIVE:
			nextState = CGRAState.RECEIVELOCALVAR;
			break;
		case RECEIVELOCALVAR:
			nextState = CGRAState.IDLE;
			break;
			//		case WRITE:
			//			nextState = CGRAState.IDLE;
			//			break;
		case WRITECONTEXT_SEND:
			nextState = CGRAState.SENDLOCALVAR;
			break;
		case SENDLOCALVAR:
			nextState = CGRAState.IDLE;
			break;
		case SETADDRESS:
			nextState = CGRAState.RUN;
			cycle = 0;
			runCounter++;
//						for(PE pe :getPEs()){
			//				for(Operator ops: pe.getModel().getAvailableNonNativeOperators().keySet()){
			//					System.out.println(pe.PeID + "  " + pe.getModel().getAvailableNonNativeOperators().get(ops).getName() + "  " + pe.getModel().getAvailableNonNativeOperators().get(ops).getLatency());
			//					
			//				}
//							for(int i = 0; i< pe.regfile.registers.length;i++){
//								System.out.println(pe.PeID + " "+i +"  " + pe.regfile.registers[i]);
//							}
//							System.out.println("\n");
//						}

			break;

		case RUN:
			if(pc == contextsize-1){
				nextState = CGRAState.IDLE;
				debugregisterfiles = false;
			}
			break;
		default: throw new AmidarSimulatorException("Not existing state found in CGRA : " + getState());
		}		
		if(nextState != null)
			setState(nextState);	
	}

	/**
	 * Resets the CGRA
	 */
	public void resetCGRA(){
		//		OutputAck = false;
		setResultAck(false);
		controlunit.setLoadEnable(false);
		enableSubmodules = false;
		contextWrite = false;
		state = CGRAState.IDLE;


		for(int i = 0; i<PEs.length ;i++){
			PEs[i].fetchContext(context[i].combinatorial());
//			PEs[i].regfile.reset();
		}
		cbox.fetchContext(contextcbox.combinatorial());
	}


	public int cycle = 0;
	public int runCounter = 0;

	/**
	 * Main function to be triggered every cycle. Is ideally split into 3 steps. 
	 * 	1) clocked signal  
	 *  2) combinatorial signals 
	 *  3) combinatorial signals due to a feedback on module level 
	 */



	public boolean executeOp(CgraOpcodes op){
			
		BundledCacheEnable = true;
		
		//========== CACHE DEV ==========
        for(int i = 0; i<caches.length; i++){
                if(!InputCacheValid[i]){
                        if(cacheTicks[i] == 0) InputCacheValid[i] = true;
                        else cacheTicks[i]--;
                }
        }
        //===============================

		

		for(boolean b: InputCacheValid){
			if(!b){
				BundledCacheEnable = false;
			}
		}	
		// only for debugging purposes
		cycle ++;
		
//		System.out.println("CGRA TTP " + controlunit.getProgramCounter());
		
		// prints register content at the beginning of a run 
		if(state == CGRAState.SETADDRESS){
			debugregisterfiles = true;
		}

		/*
		 * Bassically the Enable block of a Verilog description 
		 */
		if(BundledCacheEnable || (state != CGRAState.RUN )) {
			state = nextState;
			operateSubmodulesClocked();
		}

		switch(getState()){		
		case IDLE:
			controlunit.setLoadEnable(false);
			enableSubmodules = false;
			CtrlEnable=false;
			contextWrite = false;
			operateSubmodulesComb();
			// otherwise nothing should happen here
			break;
		case WRITECONTEXT_RECEIVE:
			stimulus.add(new StimulusAmidar(CgraInstruction.RECEIVELOCALVAR,input[OPERAND_A_LOW], input[OPERAND_B_LOW]));
			// this input operand_data is actually connected to all context inputs
			//			if(input[OPERAND_ADDRESS] > PEs.length-1 )
			//				throw new AmidarSimulatorException("Attempt to write an non existing PE with ID : " + input[OPERAND_ADDRESS]);
			int address = input[OPERAND_A_LOW] >> (model.getNrOfPEs()+2); 
		for(int i = 0; i <PEs.length;i++){
			PEs[i].setInputAmidar((int) input[OPERAND_B_LOW]);
			
			if( ((input[OPERAND_A_LOW]>>i)&1) == 1){
				ContextMaskPE mask = PEs[i].getModel().getContextMaskPE();
				long converted = 0;
				converted = mask.setAddrWr( converted, address);
				converted = mask.setWriteEnable(converted, true);
				converted = mask.setMuxReg(converted, PE.IN);
				converted = mask.setEnable(converted, true);
				context[i].setInputData(converted);
			}
			else{
				context[i].setInputData((long) 0);
			}
		}			
		//			OutputResult_low_valid = false;
		controlunit.setLoadEnable(false);
		enableSubmodules = false;
		contextWrite = true;
		CtrlEnable=false;
		operateSubmodulesComb();
		break;
		case RECEIVELOCALVAR:
			setResultAck(true);
			//			OutputResult_low_valid = false;
			controlunit.setLoadEnable(false);
			enableSubmodules = true;
			contextWrite = false;
			CtrlEnable=false;
			operateSubmodulesComb();
			break;
		case WRITECONTEXT_SEND:
			// this input operand_data is actually connected to all context inputs
			//			if(input[OPERAND_ADDRESS] > PEs.length-1 )
			//				throw new AmidarSimulatorException("Attempt to write an non existing PE with ID : " + input[OPERAND_ADDRESS]);
			address = input[OPERAND_A_LOW] >> (model.getMaxMuxAddrWidth() + model.getViaWidth());
		int mux = ( input[OPERAND_A_LOW] >> (model.getViaWidth()) ) & ~( -1 << model.getMaxMuxAddrWidth() );
		for(int i = 0; i <PEs.length;i++){
            int muxTmp = mux;
            ContextMaskPE mask = PEs[i].getModel().getContextMaskPE();
            long converted = 0;
            converted =    mask.setAddrMux(converted, address);
            converted = mask.setAddrDo(converted,address);
            if(PEs[i].getModel().getInputs().size()<mux){     // the value of mux is only relevant for the PE with the liveout conection providing the desired value
                muxTmp = 0; // for all others it is irrelevant. For some this might lead to a array index out of bound exception
            } // Thus we limit mux for those PEs. (In HW this isn't necessary)
            converted = mask.setMuxB(converted, muxTmp);
            converted = mask.setEnable(converted, true);
            context[i].setInputData(converted); 
		}			
		//			OutputResult_low_valid = false;
		controlunit.setLoadEnable(false);
		enableSubmodules = false;
		contextWrite = true;
		CtrlEnable=false;
		operateSubmodulesComb();
		break;
		case SENDLOCALVAR:
			int liveOut = input[OPERAND_A_LOW]  & ~( -1 << model.getViaWidth() ); 
			//			OutputResult_low_valid = false;
			controlunit.setLoadEnable(false);
			enableSubmodules = true;
			contextWrite = false;
			CtrlEnable=false;

			operateSubmodulesComb();
			output[RESULT_LOW] = PEs[PEsliveOut[liveOut]].getOutputCache();
			setOutputValid(RESULT_LOW);
			stimulus.add(new StimulusAmidar(CgraInstruction.SENDLOCALVAR, input[OPERAND_A_LOW], input[OPERAND_B_LOW], output[RESULT_LOW]));
			break;
		case SETADDRESS:
			stimulus.add(new StimulusAmidar(CgraInstruction.RUN, input[OPERAND_A_LOW], 99));
			
			cacheTicks = new int[cacheTicks.length];
			
			setResultAck(true);
			//			OutputResult_low_valid = false;
			controlunit.setLoadEnable(true);
			enableSubmodules = false;
			controlunit.setInputData((int)input[OPERAND_A_LOW]);
			CtrlEnable=true;
			contextWrite = true;
			for(int i = 0; i <PEs.length;i++)
				context[i].setInputData((long) 0);
			operateSubmodulesComb();
			break;
		case RUN:
			setResultAck(true);
			//			OutputResult_low_valid = false;
			controlunit.setLoadEnable(false);
			enableSubmodules = true;
			CtrlEnable=true;
			contextWrite = false;
			operateSubmodulesComb();
			break;

		default: throw new AmidarSimulatorException("Not existing state found in CGRA : " + getState());
		}

		
		nextState(op);
//		printStatusDebug();
		return nextState == CGRAState.IDLE;
	}

	boolean debugregisterfiles = false;

	/**
	 * Debug method that prints the status of the current CGRA 
	 */
	public void printStatusDebug(){


		if(debugregisterfiles){
			try {
				fwRegDebug.write("--------- cycle: "+ cycle + "(Run "+runCounter+")" +"----------\n");
				
				for(int i = 0; i <cboxslots;i++){
					int entry = (cbox.regfile[i]) ? 1 : 0;
						fwRegDebug.write(entry+"\n");
				}
				fwRegDebug.write("---\n");				
				
				for(int pe = 0;pe < getNumberOfPes();pe++){
					for(int reg = 0;reg < PEs[pe].regfile.registers.length;reg++){
						if(!PEs[pe].regfile.registerusage[reg]){
							fwRegDebug.write("x\n");
						}
						else{
							fwRegDebug.write(PEs[pe].regfile.registers[reg]+"\n");
						}
					}
					fwRegDebug.write("---\n");
				}
				
				fwRegDebug.write("\n \n \n");		

				fwSystemDebug.write("--------- cycle:"+ cycle + "(Run "+runCounter+")" +"----------\n");
				fwSystemDebug.write("ccu : "+controlunit.getProgramCounter() +"\n");
				for(PE pe:PEs){
					fwSystemDebug.write(" ------- PE " + pe.getModel().getID()  + "\n");
					fwSystemDebug.write("Context : " +pe.context + "\n");
					fwSystemDebug.write("loading entry : " + pe.contextmask.addrMux(pe.context) + "\n");
					if(pe.inputALUAdefined){
						fwSystemDebug.write("A : " + pe.inputALUA + "\n"); 
					}
					else{
						fwSystemDebug.write("A : x\n");
					}
						
//					if(pe.contextmask.muxA(pe.context) >= pe.inputs.length){
//						fwSystemDebug.write("(reg) \n");
//					}
//					else{
//						fwSystemDebug.write("(PE "+ pe.inputs[pe.contextmask.muxA(pe.context)] +") \n");
//					}
					if(pe.inputALUBdefined){
						fwSystemDebug.write("B : " + pe.inputALUB+ "\n");
					}
					else{
						fwSystemDebug.write("B : x\n");
					}
//					if(pe.contextmask.muxB(pe.context) >= pe.inputs.length){
//						fwSystemDebug.write("(reg) \n");
//					}
//					else{
//						fwSystemDebug.write("(PE "+ pe.inputs[pe.contextmask.muxB(pe.context)] +") \n");
//					}
					fwSystemDebug.write("op - " + pe.contextmask.operation(pe.context) + "\n");
					fwSystemDebug.write("R : " + pe.outputALU);
					if(pe.regfile.getWriteEnable()){
						fwSystemDebug.write(" ( -> "+ pe.regfile.getWriteAddress() +")");
					}
					fwSystemDebug.write("\n");
					if(pe.controlFlow()){
						fwSystemDebug.write("S : " + pe.getStatus() + "\n");
					}
					fwSystemDebug.write("\n");		
				}
				
				fwSystemDebug.write("------- CBOX \n");
				fwSystemDebug.write("Context : "+cbox.context+" \n");
				int pred = 0;
				if(cbox.getPredicationOutput()){
					pred = 1;
				}
				fwSystemDebug.write("Predication out: "+pred+" \n");
				int sel = 0;
				if(cbox.getOutputControlUnit()){
					sel = 1;
				}
				fwSystemDebug.write("Branchselection out: "+sel +" \n");
				fwSystemDebug.write("\n");
				
				fwSystemDebug.write("\n \n");
			} catch (IOException e) {
				System.err.println("probleme while writing debugged in cgra");
			}	
		}
	}
	
	/**
	 * First step of operate(). Triggers all clocked gates
	 */
	private void operateSubmodulesClocked(){

		controlunit.operateClocked();
		for(int i = 0; i<PEs.length ;i++){
			context[i].setInputCCNT(controlunit.getProgramCounter());
//			System.out.println("\tPPPE: "+i);
			PEs[i].regClocked();
			context[i].clocked();
		}
		contextcbox.setInputCCNT(controlunit.getProgramCounter());
		contextcbox.clocked();
		cbox.operateClocked();
	}

	/**
	 * Second step of operate(). Triggers all combinatorial processes. The order is highly important!
	 */
	private void operateSubmodulesComb() {

		
//		long tt1 = System.nanoTime();
		// store pc to propagate it
		controlunit.operateComb();	
		pc = controlunit.getProgramCounter();
		//		System.out.println("\n PC - " + pc );

		for(int i = 0; i<PEs.length ;i++){
			context[i].setInputWriteEnable(contextWrite);
			PEs[i].fetchContext(context[i].combinatorial());
			PEs[i].setInputEnable(enableSubmodules);
			PEs[i].regComb();
		}
//		long tt2 = System.nanoTime();
		int cachecnt = 0;
		for(int i = 0; i<PEs.length ;i++){
			if(BundledCacheEnable || state != CGRAState.RUN ){//stall the ALUs...
				PEs[i].operate();
			}
			if(model.getPEs().get(i).getControlFlow()){
				cbox.Input[cbox.inputmapping[i]] = PEs[i].getStatus();
			}
			///HERE DMA CACHE
			if(PEs[i].memoryAccess()){
				PEs[i].setInputCacheData(InputCacheData[cachecnt]);
				cachecnt++;
			}
		}
//		long tt3 = System.nanoTime();
		cbox.setEnable(enableSubmodules);
		cbox.fetchContext(contextcbox.combinatorial());
		cbox.operateComb();
//		long tt4 = System.nanoTime();
		// propagate outputof cbox to controlunit
		controlunit.setInputPbox(cbox.getOutputControlUnit());
		controlunit.setInputEnable(CtrlEnable);
//		long tt5 = System.nanoTime();
		cachecnt = 0;
		boolean predication = cbox.getPredicationOutput();
		for(int i = 0; i<PEs.length ;i++){
			PEs[i].setInputCBox(predication);
			PEs[i].checkException();
			// additional combinatorial signals - mainly preparation for next cycle 
			PEs[i].combinatorialLateArrival();
			if(PEs[i].memoryAccess()){
				OutputCacheData[cachecnt] = PEs[i].getOutputCache();
				OutputCacheValid[cachecnt] = PEs[i].getOutputCacheValid();
				OutputCacheWrite[cachecnt] = PEs[i].getOutputCacheWrite();
				cacheOffset[cachecnt] = PEs[i].getOffsetCache();
				cacheBaseAddress[cachecnt]	= PEs[i].getBaseAddrCache();
				
//				//========== CACHE DEV ==========
//				if(OutputCacheValid[cachecnt]){
//					if(OutputCacheWrite[cachecnt]){
//						caches[cachecnt].writeData(cacheBaseAddress[cachecnt], cacheOffset[cachecnt], OutputCacheData[cachecnt]);
////						System.out.println("CACHEWRI"+cachecnt);
////						System.out.println("\t" + cacheBaseAddress[cachecnt] + " + " + cacheOffset[cachecnt] + " : " + OutputCacheData[cachecnt]);
//					}
//					else{
//						caches[cachecnt].requestData(cacheBaseAddress[cachecnt], cacheOffset[cachecnt]);
//						InputCacheData[cachecnt] = caches[cachecnt].getData();
////						System.out.println("CACHEREA"+cachecnt);
////						System.out.println("\t" + cacheBaseAddress[cachecnt] + " + " + cacheOffset[cachecnt] + " : " + InputCacheData[cachecnt]);
//					}
//				}
//				InputCacheValid[cachecnt] = true;
//				//========== /CACHE DEV ==========
				
				//========== CACHE DEV ==========
				if(BundledCacheEnable){
					if(cacheTicks[cachecnt] == 0){
						if(OutputCacheValid[cachecnt]){
							if(OutputCacheWrite[cachecnt]){
								cacheTicks[cachecnt] = caches[cachecnt].writeData(cacheBaseAddress[cachecnt], cacheOffset[cachecnt], OutputCacheData[cachecnt]);
//								System.out.println("CACHEWRI"+cachecnt);
//								System.out.println("\t" + cacheBaseAddress[cachecnt] + " + " + cacheOffset[cachecnt] + " : " + OutputCacheData[cachecnt]);
							}
							else{
								cacheTicks[cachecnt] = caches[cachecnt].requestData(cacheBaseAddress[cachecnt], cacheOffset[cachecnt]);
								InputCacheData[cachecnt] = caches[cachecnt].getData();
//								System.out.println("CACHEREA"+cachecnt);
//								System.out.println("\t" + cacheBaseAddress[cachecnt] + " + " + cacheOffset[cachecnt] + " : " + InputCacheData[cachecnt]);
							}
//							System.err.println(cacheTicks[cachecnt]);
							if(cacheTicks[cachecnt] == 0) InputCacheValid[cachecnt] = true;
							else InputCacheValid[cachecnt] = false;
						}
					}
				}
                //========== /CACHE DEV ==========      
				
				cachecnt++;

			}
		}
//		long tt6 = System.nanoTime();
		controlunit.setInputPbox(cbox.getOutputControlUnit());
		controlunit.operateLateArrival(); // (Update PC)
//		long tt7 = System.nanoTime();
//		System.out.println("--------------------------------------------------------");
//		System.out.println(tt2-tt1);
//		System.out.println(tt3-tt2);
//		System.out.println(tt4-tt3);
//		System.out.println(tt5-tt4);
//		System.out.println(tt6-tt5);
//		System.out.println(tt7-tt6);
		
		
		
	}

	/*
	 *  Information methods for scheduling
	 */

	public int getCBoxSize(){
		return cboxslots;
	}

	
	public int getMemorySizeOfControlUnit(){
		return stateSizeControlUnit;
	}

	
	public int getMemorySizehOfContext(){
		return contextsize;
	}

	
	public Cbox getPBox() {
		return cbox;
	}

	
	public CGRAState getState() {
		return state;
	}

	
	public void setState(CGRAState state) {
		this.state = state;
	}

	
	public int[] getLiveOuts(){
		return PEsliveOut;
	}

	
	public boolean tick(){
		//		System.out.println("Ruuuuuuuuuuuuuuuuuuuning "+ controlunit.getProgramCounter() + " " + currentState);
		boolean isReady = (currentState == State.IDLE) && !tokenValid; 
		State nextState = currentState;
		if(currentState == State.IDLE){
			if(tokenValid && validInputs(opcode)){
				nextState = State.BUSY;
				trackerOpcode.add(opcode);
				trackerOperandAddr.add(input[OPERAND_A_LOW]);
				trackerOperandData.add(input[OPERAND_B_LOW]);
//				System.out.println(opcode + " " + input[OPERAND_A_LOW] + "  " + input[OPERAND_B_LOW] + "-> " + mask.getBitString(input[OPERAND_A_LOW]));
				//				count = getDuration(opcode);
			} else if(!tokenValid){
				tokenAdapter.nextToken();
			}
		} else if(currentState == State.BUSY){
			//			count--;
			//			if(count <= 0){
			if(executeOp(opcode)){
				if(executeTrace.active()){
					executeTrace.println(this.toString()+ " executed "+ opcode); //TODO
					executeTrace.println("\toutput low: "+ output[RESULT_LOW]);
				}
				if(getResultAck()){
					nextState = State.IDLE;
					setResultAck(false);
				}
				else{
					nextState = State.SENDING;
				}

				for(int i = 0; i < inputValid.length; i++){
					inputValid[i] = false;
				}
				tokenAdapter.nextToken();
			}
			//			}
		} else if(currentState == State.SENDING){
			if(getResultAck()){
				nextState = State.IDLE;
				//				for(int i = 0; i < inputValid.length; i++){
				//					inputValid[i] = false;
				//				}
				for(int i = 0; i < outputValid.length; i++){
					outputValid[i] = false;
				}
				//				tokenAdapter.nextToken();
				setResultAck(false);
			}
		}
		currentState = nextState;
		return isReady;
	}

	
		public double getAdditionalEnergy() {
		double energy = 0;
		
		for(PE pe: PEs){
			energy += pe.getDynamicEnergy();
		}

		return energy;
	}
	
		
	public double getStaticEnergy() {
		double energy = super.getStaticEnergy();
		
		for(PE pe: PEs){
			energy += pe.getStaticEnergy();
		}
		
		energy += context[0].getMemorySize()/5000;
		energy *= 0.7;
		return energy;
	}

	
	@Override
	public boolean validInputs(CgraOpcodes op) {
		switch (op) {
		case RECEIVELOCALVAR:
			return inputValid[OPERAND_B_LOW] && inputValid[OPERAND_A_LOW];
		case SENDLOCALVAR:
			return inputValid[OPERAND_A_LOW];
		case RUN:
			return inputValid[OPERAND_A_LOW];
		default:
			break;
		}
		return false;
	}
}
