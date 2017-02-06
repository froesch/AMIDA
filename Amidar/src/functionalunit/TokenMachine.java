package functionalunit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import converter.classloader.test.Pair;
import dataContainer.ByteCode;
import dataContainer.Invokation;
import dataContainer.MethodDescriptor;
import dataContainer.SynthesizedKernelDescriptor;
import exceptions.AmidarSimulatorException;
import amidar.axtLoader.AXTDataSection;
import amidar.axtLoader.AXTFile;
import amidar.axtLoader.AXTHeader;
import amidar.axtLoader.AXTLoader;
import amidar.axtLoader.AXTTableSection;
import tracer.Trace;
import tracer.TraceManager;
//import functionalunit.heap.HandleTableCache;
import functionalunit.cache.Memory;
import functionalunit.opcodes.FrameStackOpcodes;
import functionalunit.opcodes.TokenMachineOpcodes;
import functionalunit.tables.ClassTableEntry;
import functionalunit.tables.ConstantPoolEntry;
import functionalunit.tables.ExceptionTableEntry;
import functionalunit.tables.ImplementedInterfacesTableEntry;
import functionalunit.tables.InterfaceTableEntry;
import functionalunit.tables.MethodTableEntry;
import functionalunit.tables.SimpleTableCache;
import functionalunit.tables.TableCache;
import functionalunit.tokenmachine.ClassController;
import functionalunit.tokenmachine.InstructionCache;
import functionalunit.tokenmachine.Profiler;
import functionalunit.tokenmachine.SimpleInstructionCache;

/**
 * Implementing the TokenMachine
 * TODO: HW Profiler
 * TODO: Exceptions
 * TODO: GC
 * TODO: Thread Scheduler
 * @author jung
 *
 */
public abstract class TokenMachine extends FunctionalUnit<TokenMachineOpcodes> {
	
	// APPLICATION DESCRIPTION
	private int arrayTypeOffset = 0;
	private int interfaceOffset = 0;
	private int actualApplicationStart = 0;
	public boolean startedActualApplication = false;

	
	
	// VARIABLES STORING THE CURRENT CONTEXT
	protected int programCounter = 0;
	private int applicationBaseAddress = 0;	
	private int currentMethodBaseAddress = 0;
	public int instructionMemoryAddress =  applicationBaseAddress + currentMethodBaseAddress + programCounter;
	private int currentAMTI = 0;
	private boolean endOfCode = false;


	// VARIABLES STORING INTERNAL STATUS SIGNALS
	protected boolean isJump;
	private boolean executedJump;
	protected int bytecodeOffset; 
	protected int sendConstantCount;
	protected int sentConstants;
	private byte currentInstruction;
	private DecodeState decodeState = DecodeState.IDLE;	
	private int tokenState = 0;

	protected int loopIterations;
	protected boolean loopIterationsValid;


	// VARIABLES STORING INTERMEDIATE VALUES FOR METHOD INVOKATION
	private int methodRIMTI = 0;
	private int methodIOLI = 0;
	private int methodRMTI = 0;
	private int methodAMTI = 0;
	private int methodArgs = 0;
	private int methodMaxLocals = 0;
	private int methodBaseAddress = 0;
	private int interfaceTableIndex = 0;
	
	// FOR EXCEPTIONS
	private int exceptionTableIndexCounter = 0;
	private int exceptionTableIndex = 0;
	private int exceptionLength = 0;
	private int start = 0;
	private int stop = 0;
	private int expectedCTI = 0;
	private boolean handlingException = false;
	ExceptionTableEntry entry;


	// THE CACHES OF THE SYSTEM
	private InstructionCache instructionCache;
	private ClassController classController;
	private TableCache<MethodTableEntry> methodTable;
	private TableCache<ConstantPoolEntry> constantPool;
	private TableCache<ExceptionTableEntry> exceptionTable;
	private TableCache<InterfaceTableEntry> interfaceTable;


	// FUNCTIONAL UNITS
	protected IALU ialu;
	protected FALU falu;
	protected ObjectHeap heap;
	protected FrameStack frameStack;
	protected TokenMachine tokenMachine;
	protected CGRA cgra;
	
	// ENERGY VALUES
	double bytecodeEnergy = 0;
	
	// PROFILER FOR SYNTHESIS
	private Profiler profiler;
	
	
	// DATA FOR SYNTHESIS
	private int[] tokenMemory;
	private SynthesizedKernelDescriptor[] kernelTable;
	private SynthesizedKernelDescriptor currentKernel;
	private int synthConstPointer;
	
	private ArrayList<Invokation> invokationHistory; // Used as estimator for speculative method inlining
	private int invokationHistoryLength = 1024;
	
	// DEBUG INFO
	private String[] methodNames; 	// Only for Debugging, native Methods and Synthesis report
	Trace methodTracer;
	String methodPrefix = "";		// This has to be handled differently when multi threadding is supported
	
	// NATIVE METHODS
	private Map<String,Boolean> nativeMethods;
	Trace systemOutTracer;
	Trace heapTracer;

	/**
	 * Creates a new Tokenmachine with given configuration file and a tracemanager
	 * @param configFile the path to the config file
	 * @param traceManager the acutal trace manager
	 */
	public TokenMachine(String configFile, TraceManager traceManager) {
		super(TokenMachineOpcodes.class, configFile, traceManager);
		bytecodeEnergy = (Double)jsonConfig.get("bytecodeEnergy");
		profiler = new Profiler();
		tokenMemory = new int [2048];						// TODO
		kernelTable = new SynthesizedKernelDescriptor[64];  // TODO
		for(int i = 0; i < kernelTable.length; i++){
			kernelTable[i] = new SynthesizedKernelDescriptor();
			kernelTable[i].setContextPointer(255);
		}
//		kernelTable[0] = new SynthesizedKernelDescriptor();
//		kernelTable[0].setContextPointer(255);
		methodTracer = traceManager.getf("methods");
		
		invokationHistory = new ArrayList<Invokation>();
		
		systemOutTracer = traceManager.getf("system");
		heapTracer = traceManager.getf("heap");
		systemOutTracer.prefixed(false);
	}

	/**
	 * Sets all FUs so that the tokenmachine can send token to all FUs
	 * Corresponds to the token distribution network
	 * @param ialu the integer ALU
	 * @param falu the floating point ALU
	 * @param heap the heap memory
	 * @param frameStack the framestack
	 */
	public void setFUs(IALU ialu, FALU falu, ObjectHeap heap, FrameStack frameStack, CGRA cgra){
		this.ialu = ialu;
		this.falu = falu;
		this.heap = heap;
		this.frameStack = frameStack;
		this.cgra = cgra;
		this.tokenMachine = this;
	}

	/**
	 * Initializes all memories with data loaded from the AXT file.
	 * This method has to be called before a simulation is executed.
	 * Corresponds to a bootloader.
	 */
	public void initTables(AXTLoader axtLoader){
		//
		nativeMethods = new HashMap<String,Boolean>();
		nativeMethods.put("java/io/PrintStream.println()V", false);
		nativeMethods.put("java/io/PrintStream.print(I)V", false);
		nativeMethods.put("java/io/PrintStream.print(J)V", false);
		nativeMethods.put("java/io/PrintStream.print(D)V", false);
		nativeMethods.put("java/io/PrintStream.print(F)V", false);
		nativeMethods.put("java/io/PrintStream.print(Z)V", false);
		nativeMethods.put("java/io/PrintStream.print(S)V", false);
		nativeMethods.put("java/io/PrintStream.print(B)V", false);
		nativeMethods.put("java/io/PrintStream.print(C)V", false);
		nativeMethods.put("java/io/PrintStream.print([C)V", false);
		nativeMethods.put("java/io/PrintStream.print(Ljava/lang/String;)V", false);
		nativeMethods.put("java/io/PrintStream.flush()V", false);
		nativeMethods.put("java/io/FileInputStream.read()I", true);
		nativeMethods.put("java/io/FileInputStream.read([BII)I", true);
		nativeMethods.put("java/io/File.lengthA()I",true);
		nativeMethods.put("java/io/File.lengthB()I",true);
		nativeMethods.put("java/lang/System.arraycopyN(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
		nativeMethods.put("java/lang/Float.floatToIntBits(F)I", true);
		nativeMethods.put("java/lang/Float.intBitsToFloat(I)F", true);
		
		//
		TableCache<ClassTableEntry> classTable = new SimpleTableCache<ClassTableEntry>();
		TableCache<ImplementedInterfacesTableEntry> implementedInterfacesTable = new SimpleTableCache<ImplementedInterfacesTableEntry>();
		classController = new ClassController(classTable, implementedInterfacesTable);

		methodTable = new SimpleTableCache<MethodTableEntry>();
		constantPool = new SimpleTableCache<ConstantPoolEntry>();
		exceptionTable = new SimpleTableCache<ExceptionTableEntry>();
		interfaceTable = new SimpleTableCache<InterfaceTableEntry>();

		instructionCache = new SimpleInstructionCache();

		AXTFile axtFile = axtLoader.getAxtFile();
		AXTDataSection axtDataSection = axtFile.getDataSec(); // Contains Code + Constants
		AXTTableSection axtTableSection = axtFile.getTabSec();// Contains Tables
		AXTHeader axtHeader = axtFile.getHeader();


		/// MAKING DEEP COPY OF AXT - SO WE CAN REUSE AXTLoader

		//LOAD CODE
		byte [] codeMemoryOrigin = axtDataSection.getBytecode();
		byte [] codeMemory = new byte[codeMemoryOrigin.length];
		System.arraycopy(codeMemoryOrigin, 0, codeMemory, 0, codeMemoryOrigin.length);

		instructionCache.initMemory(codeMemory);
		arrayTypeOffset = axtHeader.getArrayTypeOffset(0);
		interfaceOffset = axtHeader.getInterfaceOffset(0);
		classController.setArrayTypeOffset(arrayTypeOffset);
		classController.setInterfaceOffset(interfaceOffset);
		
		// find start of actual code - everything before is static initializer
		int index = 0;
		while(codeMemory[index + ByteCode.getParamCount(codeMemory[index])+1] != ByteCode.GOTO){
			index += ByteCode.getParamCount(codeMemory[index])+1 ;
		}
		actualApplicationStart = index;
		
		//LOAD CLASSTABLE
		int classTableSize = axtTableSection.getClassTableSize();
		ClassTableEntry [] cMemory = new ClassTableEntry[classTableSize];
		for(int i = 0; i< classTableSize; i++){ //TODO getters of axtTableSection are quite ineffective....
			int[] data = new int[6];
			data[ClassTableEntry.CLASSSIZE] = axtTableSection.classTableGetObjectSize(i);
			data[ClassTableEntry.FLAGS] = axtTableSection.classTableGetClassFlags(i);
			data[ClassTableEntry.IMPL_INTERFACE_TABLE_REF] = axtTableSection.classTableGetImplInterfaceTableRefOffset(i);
			data[ClassTableEntry.INTERFACE_TABLE_REF]  = axtTableSection.classTableGetInterfaceTableRefOffset(i);
			data[ClassTableEntry.METHOD_TABLE_REF] = axtTableSection.classTableGetMethodTableRef(i);
			data[ClassTableEntry.SUPER_CTI] = axtTableSection.classTableGetSuperCTI(i);
			cMemory[i] = new ClassTableEntry(data);
		}
		classTable.initMemory(cMemory);

		
		//LOAD METHODTABLE
		int nrOfMethods = (int)(axtHeader.getNumberOfMethods() + axtHeader.getNumberOfStaticMethods());
		MethodTableEntry [] mMemory = new MethodTableEntry[nrOfMethods];	
		for(int i = 0; i < nrOfMethods; i++){
			int[] data = new int[8];
			data[MethodTableEntry.CODE_LENGTH] = axtTableSection.methodTableGetCodeLength(i);
			data[MethodTableEntry.CODE_REF] = (int)axtTableSection.methodTableGetCodeRef(i);
			data[MethodTableEntry.EXCEPTION_TABLE_LENGTH] = axtTableSection.methodTableGetExceptionTableLength(i);
			data[MethodTableEntry.EXCEPTION_TABLE_REF] = axtTableSection.methodTableGetExceptionTableRef(i);
			data[MethodTableEntry.FLAGS] = axtTableSection.methodTableGetMethodFlags(i);
			data[MethodTableEntry.MAX_LOCALS] = axtTableSection.methodTableGetMaxLocals(i);
			data[MethodTableEntry.MAX_STACK] = axtTableSection.methodTableGetMaxStack(i);
			data[MethodTableEntry.NUMBER_ARGS] = axtTableSection.methodTableGetNumArgs(i);
			mMemory[i] = new MethodTableEntry(data);

		}
		methodTable.initMemory(mMemory);

		
		//LOAD CONSTANT POOL
		int nrOfConstants = axtDataSection.getConstantPoolSize();
		ConstantPoolEntry [] constMemory = new ConstantPoolEntry[nrOfConstants];
		for(int i = 0; i < nrOfConstants; i++){
			int[] data = new int[1];
			data[0] = axtDataSection.getConstantPoolEntry(i);
			constMemory[i] = new ConstantPoolEntry(data);
		}
		constantPool.initMemory(constMemory);

		
		//LOAD EXCEPTIONTABLE
		int nrOfExceptions = axtTableSection.getExceptionTableSize();
		ExceptionTableEntry [] eMemory = new ExceptionTableEntry[nrOfExceptions];
		for(int i = 0; i < nrOfExceptions; i++){
			int[] data = new int [4];
			data[ExceptionTableEntry.CATCH_TYPE_CTI] = axtTableSection.exceptionTableGetCatchType(i);
			data[ExceptionTableEntry.END] = axtTableSection.exceptionTableGetEndPC(i);
			data[ExceptionTableEntry.PC_HANDLER] = axtTableSection.exceptionTableGetHandlerPC(i);
			data[ExceptionTableEntry.START] = axtTableSection.exceptionTableGetStartPC(i);

			eMemory[i] = new ExceptionTableEntry(data);
		}
		exceptionTable.initMemory(eMemory);

		
		//LOAD INTERFACETABLE
		int interfaceTableSize = axtTableSection.getInterfaceTableSize();
		InterfaceTableEntry [] iMemory = new InterfaceTableEntry[interfaceTableSize];
		for(int i = 0; i < interfaceTableSize; i++){
			int[] data = new int[1];
			data[0] = axtTableSection.interfaceTableGetMethodOffset(i);
			iMemory[i] = new InterfaceTableEntry(data);
		}
		interfaceTable.initMemory(iMemory);

		
		//LOAD IMPL. INTERFACES TABLE
		int nrOfImplementedInterfaces = axtTableSection.getImplementedInterfacesSize();
		ImplementedInterfacesTableEntry[] iiMemory = new ImplementedInterfacesTableEntry[nrOfImplementedInterfaces];
		int entries = axtHeader.getImplementedInterfacesEntrySize()*8;
		for(int i = 0; i< nrOfImplementedInterfaces; i++){
			int[] data = new int[entries];
			int value = axtTableSection.getImplementedInterfaces(i);
			for(int j = entries-1; j >= 0; j--){
				data[j] = value % 2;
				value = value>>>1;
			}
			iiMemory[i] = new ImplementedInterfacesTableEntry(data);
		}
		implementedInterfacesTable.initMemory(iiMemory);
	}


	@Override
	public int getNrOfInputports() {
		return 4;
	}



	@Override
	public boolean executeOp(TokenMachineOpcodes op) {
		executedJump = false;
		switch(op){
		case BRANCH_IF_LE:
			executedJump = true;
			profiler.jump(instructionMemoryAddress, input[OPERAND_A_LOW], currentAMTI);
			if(input[OPERAND_B_LOW] != 1){
				programCounter += input[OPERAND_A_LOW];
			}else{
				programCounter += 3;
			}
			setResultAck(true);
			break;
		case BRANCH_IF_GT:
			executedJump = true;
			profiler.jump(instructionMemoryAddress, input[OPERAND_A_LOW], currentAMTI);
			if(input[OPERAND_B_LOW] == 1){
				programCounter += input[OPERAND_A_LOW];
			}else{
				programCounter += 3;
			}
			setResultAck(true);
			break;
		case BRANCH_IF_GE:
			executedJump = true;
			profiler.jump(instructionMemoryAddress, input[OPERAND_A_LOW], currentAMTI);
			if(input[OPERAND_B_LOW] != -1){
				programCounter += input[OPERAND_A_LOW];
			}else{
				programCounter += 3;
			}
			setResultAck(true);
			break;
		case BRANCH_IF_LT:
			executedJump = true;
			profiler.jump(instructionMemoryAddress, input[OPERAND_A_LOW], currentAMTI);
			if(input[OPERAND_B_LOW] == -1){
				programCounter += input[OPERAND_A_LOW];
			}else{
				programCounter += 3;
			}
			setResultAck(true);
			break;
		case BRANCH_IF_NE:
			executedJump = true;
			profiler.jump(instructionMemoryAddress, input[OPERAND_A_LOW], currentAMTI);
			if(input[OPERAND_B_LOW] != 0){
				programCounter += input[OPERAND_A_LOW];
			}else{
				programCounter += 3;
			}
			setResultAck(true);
			break;
		case BRANCH_IF_EQ:
			executedJump = true;
			profiler.jump(instructionMemoryAddress, input[OPERAND_A_LOW], currentAMTI);
			if(input[OPERAND_B_LOW] == 0){
				programCounter += input[OPERAND_A_LOW];
			}else{
				programCounter += 3;
			}
			setResultAck(true);
			break;
		case BRANCH:
			executedJump = true;
			profiler.jump(instructionMemoryAddress, input[OPERAND_A_LOW], currentAMTI);
			programCounter += input[OPERAND_A_LOW];
			setResultAck(true);
			break;
		case CLASSSIZE:
			if(tokenState == 0){
				classController.requestClassInfo(input[OPERAND_A_LOW]);
				tokenState = 1;
				return false;
			} else {
				if(classController.requestClassInfo(input[OPERAND_A_LOW])){
					output[RESULT_LOW]= classController.getClassSize();
					setOutputValid(RESULT_LOW);
				} else {
					return false;
				}
			}
			break;
		case SENDBYTECODE_1:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+1);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+1)){
					output[RESULT_LOW]= instructionCache.getData();
					setOutputValid(RESULT_LOW);
					tokenState = 0;
					sentConstants++;
				} else {
					return false;
				}
			}
			break;
		case SENDBYTECODE_2:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+2);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+2)){
					output[RESULT_LOW]= instructionCache.getData();
					setOutputValid(RESULT_LOW);
					tokenState = 0;
					sentConstants++;
				} else {
					return false;
				}
			}
			break;
		case SENDBYTECODE_3:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+3);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+3)){
					output[RESULT_LOW]= instructionCache.getData();
					setOutputValid(RESULT_LOW);
					tokenState = 0;
					sentConstants++;
				} else {
					return false;
				}
			}
			break;
		case SENDBYTECODE_1_2:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+1);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+1)){
					output[RESULT_LOW]= ((int)instructionCache.getData())<<8;
					tokenState = 2;
				}
				return false;
			case 2:
				instructionCache.requestData(instructionMemoryAddress+2);
				tokenState = 3;
				return false;
			case 3:
				if(instructionCache.requestData(instructionMemoryAddress+2)){
					output[RESULT_LOW] |= ((int)(instructionCache.getData())&0xFF);
					setOutputValid(RESULT_LOW);
					tokenState = 0;
					sentConstants++;
				} else {
					return false;
				}
			}
			break;
		case SENDBYTECODE_3_4:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+3);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+3)){
					output[RESULT_LOW]= ((int)instructionCache.getData())<<8;
					tokenState = 2;
				}
				return false;
			case 2:
				instructionCache.requestData(instructionMemoryAddress+4);
				tokenState = 3;
				return false;
			case 3:
				if(instructionCache.requestData(instructionMemoryAddress+4)){
					output[RESULT_LOW] |= ((int)(instructionCache.getData())&0xFF);
					setOutputValid(RESULT_LOW);
					tokenState = 0;
					sentConstants++;
				} else {
					return false;
				}
			}
			break;
		case SENDBYTECODE_1_2_3_4:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+1);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+1)){
					output[RESULT_LOW]= ((int)instructionCache.getData())<<24;
					tokenState = 2;
				}
				return false;
			case 2:
				instructionCache.requestData(instructionMemoryAddress+2);
				tokenState = 3;
				return false;
			case 3:
				if(instructionCache.requestData(instructionMemoryAddress+2)){
					output[RESULT_LOW] |= (((int)instructionCache.getData())&0xFF)<<16;
					tokenState = 4;
				}
				return false;
			case 4:
				instructionCache.requestData(instructionMemoryAddress+3);
				tokenState = 5;
				return false;
			case 5:
				if(instructionCache.requestData(instructionMemoryAddress+3)){
					output[RESULT_LOW] |= (((int)instructionCache.getData())&0xFF)<<8;
					tokenState = 6;
				}
				return false;
			case 6:
				instructionCache.requestData(instructionMemoryAddress+4);
				tokenState = 7;
				return false;
			case 7:
				if(instructionCache.requestData(instructionMemoryAddress+4)){
					output[RESULT_LOW] |= ((int)(instructionCache.getData()))&0xFF;
					setOutputValid(RESULT_LOW);
					tokenState = 0;
					sentConstants++;
				} else {
					return false;
				}
			}

			break;
		case LOAD_ARG_IOLI_RIMTI:
			methodArgs = (input[OPERAND_A_LOW]>>26)&0x3F;
			methodIOLI = (input[OPERAND_A_LOW]>>16)&0x3FF;
			methodRIMTI = (input[OPERAND_A_LOW])&0xFFFF;
			output[RESULT_LOW] = methodArgs;
			setOutputValid(RESULT_LOW);
			break;
		case LOAD_ARG_RMTI:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+1);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+1)){
					output[RESULT_LOW]= ((int)instructionCache.getData())<<8;
					tokenState = 2;
				}
				return false;
			case 2:
				instructionCache.requestData(instructionMemoryAddress+2);
				tokenState = 3;
				return false;
			case 3:
				if(instructionCache.requestData(instructionMemoryAddress+2)){
					output[RESULT_LOW] |= ((int)(instructionCache.getData())&0xFF);
					methodRMTI = output[RESULT_LOW]&0x3FF;
					methodArgs = (output[RESULT_LOW]>>10)&0x3F;
					output[RESULT_LOW] = methodArgs;
					setOutputValid(RESULT_LOW);
					tokenState = 0;
					sentConstants++;
				} else {
					return false;
				}
			}
			break;
		case LDC:
			switch(tokenState){
			case 0: 
				constantPool.requestData(input[OPERAND_A_LOW]);
				tokenState = 1;
				return false;
			case 1:
				if(constantPool.requestData(input[OPERAND_A_LOW])){
					output[RESULT_LOW] = constantPool.getData().get(0);
					setOutputValid(RESULT_LOW);
					tokenState = 0;
				} else {
					return false;
				}
			}
			break;
		case LDC2:
			switch(tokenState){
			case 0: 
				constantPool.requestData(input[OPERAND_A_LOW]);
				tokenState = 1;
				return false;
			case 1:
				if(constantPool.requestData(input[OPERAND_A_LOW])){
					output[RESULT_HIGH] = constantPool.getData().get(0);
					tokenState = 2;
				}
				return false;
			case 2:
				constantPool.requestData(input[OPERAND_A_LOW]+1);
				tokenState = 3;
				return false;
			case 3:
				if(constantPool.requestData(input[OPERAND_A_LOW]+1)){
					output[RESULT_LOW] = constantPool.getData().get(0);
					setOutputValid(RESULT_HIGH);
					setOutputValid(RESULT_LOW);
					tokenState = 0;
				} else {
					return false;
				}
			}
			break;
		case INVOKE_STATIC:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+1);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+1)){
					methodAMTI = ((int)instructionCache.getData())<<8;
					tokenState = 2;
				}
				return false;
			case 2:
				instructionCache.requestData(instructionMemoryAddress+2);
				tokenState = 3;
				return false;
			case 3:
				if(instructionCache.requestData(instructionMemoryAddress+2)){
					methodAMTI |= ((int)(instructionCache.getData())&0xFF);
					sentConstants++;
					methodTable.requestData(methodAMTI);
					tokenState = 4;
				}
				return false;
			case 4:
				if(methodTable.requestData(methodAMTI)){
					MethodTableEntry method = methodTable.getData();
					methodBaseAddress = method.get(MethodTableEntry.CODE_REF);
					methodArgs = method.get(MethodTableEntry.NUMBER_ARGS);
					methodMaxLocals = method.get(MethodTableEntry.MAX_LOCALS);
					if( nativeMethods.containsKey(methodNames[methodAMTI])){
						// NATIVE METHOD
						if(frameStack.opcode != FrameStackOpcodes.INVOKE || heap.currentState != State.IDLE){
							return false;
						}
						int result = executeNativeMethod(methodNames[methodAMTI]);
						output[RESULT_LOW] = result;
						output[RESULT_HIGH] = (methodMaxLocals << 16) | (methodArgs)&0xFF | 0x100;   // <-- telling the framestack that this is native
						if(nativeMethods.get(methodNames[methodAMTI])){
							output[RESULT_HIGH] |= 0x200;											 // <-- telling the framestack that this native has a return value
						}
						setOutputValid(RESULT_LOW);
						setOutputValid(RESULT_HIGH);
						programCounter += 3;
						executedJump = true;
						tokenState = 0;
						if(methodTracer.active()){
							methodTracer.println(methodPrefix + " native Method "+ methodNames[methodAMTI]);
						}
					} else {  
						// Send Results
						output[RESULT_LOW] = (currentAMTI<< 16 )| (programCounter + 3)&0xFFFF;
						output[RESULT_HIGH] = (methodMaxLocals << 16) | (methodArgs)&0xFF;
						setOutputValid(RESULT_LOW);
						setOutputValid(RESULT_HIGH);
						// Set new context
//						System.out.println("INVOKING " +currentAMTI + "->" + methodAMTI +  " pc : " + programCounter);
						programCounter = 0;
						currentAMTI = methodAMTI;
						currentMethodBaseAddress = methodBaseAddress;
						//
						executedJump = true;
						tokenState = 0;
						if(methodTracer.active()){
							methodTracer.println(methodPrefix+"\\ "+ methodNames[currentAMTI]);
							methodPrefix = methodPrefix + "|";
						}
					}
				} else {
					return false;
				}
			}
			break;
		case INVOKE:
			switch(tokenState){
			case 0:
				if((input[OPERAND_A_LOW]&0xFFFF) == 0xFFFF){
					throw new AmidarSimulatorException("Nullpointer Exception while invoking. RMTI: "+methodRMTI);
				}
				tokenState  = 1;
				return false;
			case 1:
				if(classController.requestClassInfo(input[OPERAND_A_LOW])){
					methodAMTI = methodRMTI + classController.getData().get(ClassTableEntry.METHOD_TABLE_REF);
					methodTable.requestData(methodAMTI);
					addInvokation(instructionMemoryAddress, input[OPERAND_A_LOW], methodAMTI);
					tokenState = 2;
				}
				return false;
			case 2:
				if(methodTable.requestData(methodAMTI)){
					MethodTableEntry method = methodTable.getData();
					methodBaseAddress = method.get(MethodTableEntry.CODE_REF);
					methodArgs = method.get(MethodTableEntry.NUMBER_ARGS);
					methodMaxLocals = method.get(MethodTableEntry.MAX_LOCALS);
					if( nativeMethods.containsKey(methodNames[methodAMTI])){
						// NATIVE METHOD
						if(frameStack.opcode != FrameStackOpcodes.INVOKE || heap.currentState != State.IDLE){
							return false;
						}
						int result = executeNativeMethod(methodNames[methodAMTI]);
						output[RESULT_LOW] = result;
						output[RESULT_HIGH] = (methodMaxLocals << 16) | (methodArgs)&0xFF | 0x100;   // <-- telling the framestack that this is native
						if(nativeMethods.get(methodNames[methodAMTI])){
							output[RESULT_HIGH] |= 0x200;											 // <-- telling the framestack that this native has a return value
						}
						setOutputValid(RESULT_LOW);
						setOutputValid(RESULT_HIGH);
						programCounter += 3;
						executedJump = true;
						tokenState = 0;
						if(methodTracer.active()){
							methodTracer.println(methodPrefix + " native Method "+ methodNames[methodAMTI]);
						}
					} else {  
						// NON NATIVE METHOD
						// Send Results
						output[RESULT_LOW] = (currentAMTI<< 16 )| (programCounter + 3)&0xFFFF;
						output[RESULT_HIGH] = (methodMaxLocals << 16) | (methodArgs)&0xFF;
						setOutputValid(RESULT_LOW);
						setOutputValid(RESULT_HIGH);
						// Set new context
						programCounter = 0;
						currentAMTI = methodAMTI;
						currentMethodBaseAddress = methodBaseAddress;
						//
						executedJump = true;
						tokenState = 0;
						if(methodTracer.active()){
							methodTracer.println(methodPrefix+"\\ "+ methodNames[currentAMTI]);
							methodPrefix = methodPrefix + "|";
						}
					}
				} else {
					return false;
				}
			}
			break;
		case INVOKE_INTERFACE:
			switch(tokenState){
			case 0:
				classController.requestClassInfo(input[OPERAND_A_LOW]);
				tokenState  = 1;
				return false;
			case 1:
				if(classController.requestClassInfo(input[OPERAND_A_LOW])){
					methodAMTI = classController.getData().get(ClassTableEntry.METHOD_TABLE_REF);
					interfaceTableIndex = classController.getData().get(ClassTableEntry.INTERFACE_TABLE_REF) + methodIOLI;
					interfaceTable.requestData(interfaceTableIndex);
					tokenState = 2;
				}
				return false;
			case 2:
				if(interfaceTable.requestData(interfaceTableIndex)){
					methodAMTI += methodRIMTI + interfaceTable.getData().get(0);
					methodTable.requestData(methodAMTI);
					addInvokation(instructionMemoryAddress, input[OPERAND_A_LOW], methodAMTI);
					tokenState = 4;
				}
				return false;
			case 4:
				if(methodTable.requestData(methodAMTI)){
//					System.out.println("CALLINTE");
					MethodTableEntry method = methodTable.getData();
					methodBaseAddress = method.get(MethodTableEntry.CODE_REF);
					methodArgs = method.get(MethodTableEntry.NUMBER_ARGS);
					methodMaxLocals = method.get(MethodTableEntry.MAX_LOCALS);
					// Send Results
					output[RESULT_LOW] = (currentAMTI<< 16 )| (programCounter + 5)&0xFFFF;
					output[RESULT_HIGH] = (methodMaxLocals << 16) | (methodArgs)&0xFF;
					setOutputValid(RESULT_LOW);
					setOutputValid(RESULT_HIGH);
					// Set new context
					programCounter = 0;
					currentAMTI = methodAMTI;
					currentMethodBaseAddress = methodBaseAddress;
					//
					executedJump = true;
					tokenState = 0;
					if(methodTracer.active()){
						methodTracer.println(methodPrefix+"\\ "+ methodNames[currentAMTI]);
						methodPrefix = methodPrefix + "|";
					}
				} else {
					return false;
				}
			}
			break;
		case JSR:
			output[RESULT_LOW] = programCounter + 3;
			setOutputValid(RESULT_LOW);
			programCounter += input[OPERAND_A_LOW];
			executedJump = true;
			break;
		case RET:
			programCounter = input[OPERAND_A_LOW];
			executedJump = true;
			setResultAck(true);
			break;
		case RETURN:
			switch(tokenState){
			case 0: 
				programCounter = input[OPERAND_A_LOW]&0xFFFF;
				methodAMTI = (input[OPERAND_A_LOW]>>16);
				methodTable.requestData(methodAMTI);
				tokenState = 1;
//				System.out.println("CURR AMTI " + currentAMTI);
				return false;
			case 1:
				if(methodTable.requestData(methodAMTI)){
					currentMethodBaseAddress = methodTable.getData().get(MethodTableEntry.CODE_REF);
					if(methodTracer.active()){
						methodPrefix = methodPrefix.substring(0, methodPrefix.length()-1);
						methodTracer.println(methodPrefix+ "/ "+ methodNames[currentAMTI]);
						
						methodTracer.println(methodPrefix+ " "+ methodNames[methodAMTI]);
					}
					currentAMTI = methodAMTI;
//					System.out.println("NEW AMTI " + currentAMTI + " " + programCounter);
					executedJump = true;
					tokenState = 0;
					if(handlingException){
						currentInstruction = ByteCode.ATHROW;
						programCounter -=1; // RETURN VALEU
					}
				} else {
					return false;
				}
			}
			setResultAck(true);
			break;			
		case NEWARRAY_CTI:
			switch(tokenState){
			case 0:
				classController.requestClassInfo(input[OPERAND_A_LOW]+ arrayTypeOffset);
				tokenState = 1;
				return false;
			case 1:
				if(classController.requestClassInfo(input[OPERAND_A_LOW]+ arrayTypeOffset)){
					ClassTableEntry classTableEnty = classController.getData();
					if(classTableEnty.get(ClassTableEntry.CLASSSIZE) == 3 || classTableEnty.get(ClassTableEntry.CLASSSIZE) == 7){
						output[RESULT_LOW] = 1<<31;
					} else {
						output[RESULT_LOW] = 0;
					}
					output[RESULT_LOW] |= (input[OPERAND_A_LOW]+arrayTypeOffset);
					setOutputValid(RESULT_LOW);
					tokenState = 0;		
				}
			}
			break;
		case INSTANCEOF:
			switch (tokenState) {
			case 0:
				classController.instanceOf(input[OPERAND_A_LOW], input[OPERAND_B_LOW]);
				if(classController.ready()){
					tokenState = 2;
				} else {
					tokenState = 1;
				}
				return false;
			case 1:
				if(classController.ready()){
					tokenState = 2;
				} else {
					tokenState = 1;
				}
				return false;
			case 2:
				output[RESULT_LOW] = classController.isInstanceOf();
				if(input[OPERAND_A_LOW] == 0xFFFF){
					output[RESULT_LOW] = 0;
				}
				setOutputValid(RESULT_LOW);
				tokenState = 0;
			default:
				break;
			}
			break;
		case CHECKCAST:
			switch (tokenState) {
			case 0:
				classController.instanceOf(input[OPERAND_A_LOW], input[OPERAND_B_LOW]);
				if(classController.ready()){
					tokenState = 2;
				} else {
					tokenState = 1;
				}
				return false;
			case 1:
				if(classController.ready()){
					tokenState = 2;
				} else {
					tokenState = 1;
				}
				return false;
			case 2:
				if( classController.isInstanceOf() != 1){
					throw new AmidarSimulatorException("Cannot Cast!!");
				}
				setResultAck(true);
				tokenState = 0;
			default:
				break;
			}
			break;
		case THROW:
			switch (tokenState){
			case 0:
				handlingException = true;
				if(input[OPERAND_A_LOW] == 0xFFFF){
					//TODO
					throw new AmidarSimulatorException("NullPointer Exception while Throwing an Exception");
				}
				if(currentAMTI == 0){
//					throw new AmidarSimulatorException("KAKKE EY " + input[OPERAND_A_LOW] + " " + input[OPERAND_B_LOW]);
					throw new AmidarSimulatorException("An exception was thrown on AMIDAR processor. Activate trace 'methods' in config/trace.json to find out more");
				}
				methodTable.requestData(currentAMTI);
				tokenState = 1;
				exceptionTableIndexCounter = 0;
				return false;
			case 1:
				if(methodTable.requestData(currentAMTI)){
					exceptionTableIndex = methodTable.getData().get(MethodTableEntry.EXCEPTION_TABLE_REF);
					exceptionLength = methodTable.getData().get(MethodTableEntry.EXCEPTION_TABLE_LENGTH);
					exceptionTable.requestData(exceptionTableIndex);
					tokenState = 2;
				}
				return false;
			case 2:
				if(exceptionTable.requestData(exceptionTableIndex)){
					if(exceptionTableIndexCounter >= exceptionLength ){
						// NO Exception found - throw exception again at calling method
						currentInstruction = ByteCode.ARETURN;
						output[RESULT_LOW] = input[OPERAND_B_LOW];
						setOutputValid(RESULT_LOW);
						executedJump = true;
						tokenState  = 0;
						return true;
					}
					entry = exceptionTable.getData();
					start = entry.get(ExceptionTableEntry.START);
					stop = entry.get(ExceptionTableEntry.END);
					expectedCTI = entry.get(ExceptionTableEntry.CATCH_TYPE_CTI);
					if(programCounter< start || programCounter >= stop){
						exceptionTableIndex++;
						exceptionTableIndexCounter++;
						exceptionTable.requestData(exceptionTableIndex);
						return false;
					} else if(expectedCTI == input[OPERAND_A_LOW]){
						int addr = entry.get(ExceptionTableEntry.PC_HANDLER);
						programCounter = addr;
						executedJump = true;
						tokenState = 0;
						output[RESULT_LOW] = input[OPERAND_B_LOW];
						setOutputValid(RESULT_LOW);
						handlingException = false;
						return true;
					} else { // Check parent
						classController.instanceOf(input[OPERAND_A_LOW], expectedCTI);
						if(classController.ready()){
							tokenState = 4;
						} else {
							tokenState = 3;
						}
						return false;
					}
				}
			case 3:
				if(classController.ready()){
					tokenState = 4;
				} else {
					tokenState = 3;
				}
				return false;
			case 4:
				if(classController.isInstanceOf() == 1){
					int addr = entry.get(ExceptionTableEntry.PC_HANDLER);
					programCounter = addr;
					executedJump = true;
					tokenState = 0;
					output[RESULT_LOW] = input[OPERAND_B_LOW];
					setOutputValid(RESULT_LOW);
					handlingException = false;
					return true;
				} else {
					exceptionTableIndex++;
					exceptionTableIndexCounter++;
					exceptionTable.requestData(exceptionTableIndex);
					tokenState = 2;
				}
				return false;
				
			}
			break;
		case NOP_CONST:
			break;
		case THREADSWITCH:
			break;
		case FORCESCHEDULING:
			break;
		case LOOP_0_BYTECODE_1:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+1);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+1)){
					loopIterations = instructionCache.getData();
					loopIterationsValid = true;
					setResultAck(true);
					tokenState = 0;
				} else {
					return false;
				}
			}
			break;
		case LOOP_0_BYTECODE_2:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+2);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+2)){
					loopIterations = instructionCache.getData();
					loopIterationsValid = true;
					setResultAck(true);
					tokenState = 0;
				} else {
					return false;
				}
			}
			break;
		case LOOP_0_BYTECODE_3:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+3);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+3)){
					loopIterations = instructionCache.getData();
					loopIterationsValid = true;
					setResultAck(true);
					tokenState = 0;
				} else {
					return false;
				}
			}
			break;
		case LOOP_0_BYTECODE_4:
			switch(tokenState){
			case 0:
				instructionCache.requestData(instructionMemoryAddress+4);
				tokenState = 1;
				return false;
			case 1:
				if(instructionCache.requestData(instructionMemoryAddress+4)){
					loopIterations = instructionCache.getData();
					loopIterationsValid = true;
					setResultAck(true);
					tokenState = 0;
				} else {
					return false;
				}
			}
			break;
		case SYNTH_INIT:
			currentKernel = kernelTable[input[OPERAND_A_LOW]];
			synthConstPointer = currentKernel.getSynthConstPointer();
			output[RESULT_LOW] = currentKernel.getContextPointer();
			setOutputValid(RESULT_LOW);
			break;
		case LOAD_SYNTH_CONST:
			
			output[RESULT_LOW] = tokenMemory[synthConstPointer++];
			
			setOutputValid(RESULT_LOW);
			break;
		default:
		}

		return true;
	}

	@Override
	public boolean validInputs(TokenMachineOpcodes op) {
		switch(op){
		case BRANCH_IF_LE:
		case BRANCH_IF_GT:
		case BRANCH_IF_GE:
		case BRANCH_IF_LT:
		case BRANCH_IF_NE:
		case BRANCH_IF_EQ:
		case INSTANCEOF:
		case CHECKCAST:
		case THROW:
			return inputValid[OPERAND_A_LOW] && inputValid[OPERAND_B_LOW];
		case BRANCH:
		case CLASSSIZE:
		case LOAD_ARG_IOLI_RIMTI:
		case LDC:
		case LDC2:
		case JSR:
		case RET:
		case INVOKE:
		case INVOKE_INTERFACE:
		case RETURN:
		case NEWARRAY_CTI:
		case SYNTH_INIT:
			return inputValid[OPERAND_A_LOW];
		default: return true;

		}
	}

	public boolean tick(){
		boolean ready = super.tick();			// Normal token execution
		ready  &= tickDecoder();				// Decode FSM
		return ready;
	}

	
	/**
	 * The states of the Decoder FSM 
	 * @author jung
	 *
	 */
	private enum DecodeState{
		IDLE,
		TOKEN_TREE,
		TOKEN_MATRIX,
		DONE,		// Sending Token from Token matrix is done
		FINISHED 	// Application is finished
	}
	
	/**
	 * Handles the Decoder FSM
	 * @return true if the decoder finished
	 */
	private boolean tickDecoder(){
		if(decodeState == DecodeState.IDLE){
			instructionMemoryAddress = applicationBaseAddress + currentMethodBaseAddress + programCounter;
			if(!handlingException){
				if(instructionMemoryAddress == actualApplicationStart){
					startedActualApplication = true;
					profiler = new Profiler();
					profiler.setMethodNames(methodNames);
				}
				if(instructionCache.requestData(instructionMemoryAddress)){
					profiler.newByteCode(instructionMemoryAddress);
					currentInstruction = instructionCache.getData();
					decodeState = DecodeState.TOKEN_TREE;
				}
			} else {
				decodeState = DecodeState.TOKEN_MATRIX;
			}
		}else if(decodeState == DecodeState.TOKEN_TREE){
			if(currentInstruction == ByteCode.GOTO && currentAMTI == 0){
				decodeState = DecodeState.FINISHED;
				endOfCode = true;
			} else if(currentInstruction == ByteCode.NOP || currentInstruction == (byte)0xC2 || currentInstruction == (byte)0xC3){ // Special case as no Token have to be sent
				isJump = false;
				bytecodeOffset = 0;
				sendConstantCount = 0;
				decodeState = DecodeState.DONE;
			} else {
				decodeState = DecodeState.TOKEN_MATRIX;
			}
		}else if(decodeState == DecodeState.TOKEN_MATRIX){
//			if(instructionMemoryAddress == 28126){
//				System.out.println("DECODING "+ instructionMemoryAddress + "  " + ByteCode.debug(currentInstruction) +" " + programCounter);
//			}
			decodeByteCode(currentInstruction);
			decodeState = DecodeState.DONE;
		}else if(decodeState == DecodeState.DONE){
			boolean allTokensAccepted = tokenMachine.tokenAccepted() && heap.tokenAccepted() && ialu.tokenAccepted() && falu.tokenAccepted() && frameStack.tokenAccepted();
			if(allTokensAccepted){
				if(tokenDecodingDone()&& sendConstantsDone()){
					if(isJump){
						if(executedJump){
							decodeState = DecodeState.IDLE;
							executedJump = false;
							sentConstants = 0;
						}
					} else {
						programCounter += bytecodeOffset + 1;
						decodeState = DecodeState.IDLE;
						sentConstants = 0;
					}
				}else if(!tokenDecodingDone()){
					decodeState = DecodeState.TOKEN_MATRIX;
				}
			}
		}
		return endOfCode; 
	}

	/**
	 * Denotes whether all bytecode parameters of the current bytecode have been read form the instruction cache.
	 * (Need to know whether the next bytecode can beloaded).
	 * @return true if all bytecode parameters have been procssed
	 */
	private boolean sendConstantsDone(){
		return sentConstants >= sendConstantCount;
	}


	/**
	 * Decodes the current bytecode and send the correct token to the FUs
	 * The actual method is generated by ADLA
	 * @param code the current bytecode
	 */
	public abstract void decodeByteCode(byte code);

	/**
	 * Denotes whether all token the current bytecode have been sent.
	 * If not the method decodeByteCode(byte code) has to be called again on the same code
	 * @return true if all token have been sent and the next bytecode can be loaded
	 */
	public abstract boolean tokenDecodingDone();

	public Profiler getProfiler() {
		return profiler;
	}
	
	private void addInvokation(int address, int cti, int amti){
		invokationHistory.add(new Invokation(address, cti, amti));
		if(invokationHistory.size() >= invokationHistoryLength){
			invokationHistory.remove(0);
		}
	}
	
	/**
	 * Energy produced by TokenMachine
	 */
	public double getAdditionalEnergy() {
		double energy = profiler.getGlobalBytecodeCount() * bytecodeEnergy;
		
		//TODO add cache miss energy
		
		return energy;
	}

	
	/**
	 * PSEUDO PERIPHERAL ACCESS
	 * @return
	 */
	public MethodDescriptor[] getMethodTable() {
		SimpleTableCache<MethodTableEntry> method = (SimpleTableCache<MethodTableEntry>) methodTable;
		
		MethodTableEntry[] methods = method.getMemory();
		MethodDescriptor[] result = new MethodDescriptor[methods.length];
		
		for(int i = 0; i<methods.length; i++){
			MethodDescriptor  md =new MethodDescriptor();
			MethodTableEntry mte = methods[i];
			md.setFlags(mte.get(MethodTableEntry.FLAGS));
			md.setCodeLength(mte.get(MethodTableEntry.CODE_LENGTH));
			md.setCodeRef(mte.get(MethodTableEntry.CODE_REF));
			md.setMaxLocals(mte.get(MethodTableEntry.MAX_LOCALS));
			md.setMaxStack(mte.get(MethodTableEntry.MAX_STACK));
			md.setNumberOfArgs(mte.get(MethodTableEntry.NUMBER_ARGS));
			md.setExceptionTableLength(mte.get(MethodTableEntry.EXCEPTION_TABLE_LENGTH));
			md.setExceptionTableRef(mte.get(MethodTableEntry.EXCEPTION_TABLE_REF));
			md.setMethodName(methodNames[i]);
			result[i] = md;
		}

		return result;
	}

	/**
	 * PSEUDO PERIPHERAL ACCESS
	 * @return
	 */
	public SynthesizedKernelDescriptor[] getKernelTable() {
		return kernelTable;
	}

	/**
	 * PSEUDO PERIPHERAL ACCESS
	 * @return
	 */
	public byte[] getCode() {
		return ((SimpleInstructionCache)instructionCache).getMemory();
	}
	
	/**
	 * PSEUDO PERIPHERAL ACCESS
	 * @return
	 */
	public int[] getTokenMemory() {
		return tokenMemory;
	}
	
	/**
	 * PSEUDO PERIPHERAL ACCESS
	 */
	public ArrayList<Invokation> getInvocationHistory(){
		return invokationHistory;
	}
	
	/**
	 * ONLY FOR DEBUGGING
	 * @param methodNames
	 */
	public void setMethodNames(String[] methodNames) {
		this.methodNames = methodNames;
		profiler.setMethodNames(methodNames);
	}
	
	/**
	 * ONLY FOR DEBUGGING
	 */
	public String[] getMethodNames(){
		return methodNames;
	}
	
	/**
	 * Maps Handle of Simulated FileInputStream to native FileInputStream
	 */
	HashMap<Integer,FileInputStream> in = new HashMap<Integer,FileInputStream>();
	HashMap<Integer,File> files = new HashMap<Integer,File>();
	
	private long nativeMethodLongBuffer;
	
	/**
	 * NATIVE METHODS
	 */
	private int executeNativeMethod(String method){
		if(method.equals("java/io/PrintStream.println()V")){
			systemOutTracer.println();
			return 0;
		} else if(method.equals("java/io/PrintStream.print(I)V")){
			systemOutTracer.print(frameStack.memory[frameStack.stackPointer-1]);
			return 0;
		} else if (method.equals("java/io/PrintStream.print(D)V")){
			long val = (((long)frameStack.memory[frameStack.stackPointer-1])<<32)|((long)frameStack.memory[frameStack.stackPointer-2] & 0x00000000FFFFFFFFL);
			systemOutTracer.print(Double.longBitsToDouble(val));
			return 0;
		} else if (method.equals("java/io/PrintStream.print(J)V")){
			long val = (((long)frameStack.memory[frameStack.stackPointer-1])<<32)|((long)frameStack.memory[frameStack.stackPointer-2] & 0x00000000FFFFFFFF);
			systemOutTracer.print(val);
			return 0;
		} else if (method.equals("java/io/PrintStream.print(F)V")){
			systemOutTracer.print(Float.intBitsToFloat(frameStack.memory[frameStack.stackPointer-1]));
			return 0;
		} else if (method.equals("java/io/PrintStream.print(Z)V")){
			systemOutTracer.print(frameStack.memory[frameStack.stackPointer-1] == 1);
			return 0;
		} else if (method.equals("java/io/PrintStream.print(B)V")){
			systemOutTracer.print(frameStack.memory[frameStack.stackPointer-1]);
			return 0;
		} else if (method.equals("java/io/PrintStream.print(S)V")){
			systemOutTracer.print(frameStack.memory[frameStack.stackPointer-1]);
			return 0;
		} else if (method.equals("java/io/PrintStream.print(C)V")){
			systemOutTracer.print((char)frameStack.memory[frameStack.stackPointer-1]);
			return 0;
		} else if (method.equals("java/io/PrintStream.print(Ljava/lang/String;)V")){
//			systemOutTracer.println(frameStack.memory[frameStack.stackPointer-1]);
			int handle = frameStack.memory[frameStack.stackPointer-1];
			heap.objectCache.requestData(handle, 2);
			int length = heap.objectCache.getData();
			heap.objectCache.requestData(handle, 3);
			int offset = heap.objectCache.getData();
			heap.objectCache.requestData(handle, 0);
			int charHandle = heap.objectCache.getData();

//			int length = heap.mem.getSizeHT(charHandle);
			char[] res = new char[length];
			for(int i = 0; i< length; i++){
				heap.objectCache.requestData(charHandle, i+offset);
				res[i] = (char)heap.objectCache.getData();
			}
			systemOutTracer.print(new String(res));
			
			return 0;
		} else if(method.equals("java/io/PrintStream.print([C)V")){
			Memory mem = heap.mem;
			int handle = frameStack.memory[frameStack.stackPointer-1];
			int addr = mem.getAddrHT(handle);
			int length = mem.getSizeHT(handle);
			char[] res = new char[length];
			for(int i = 0; i< length; i++){
				heap.objectCache.requestData(handle, i);
				res[i] = (char)heap.objectCache.getData();
			}
			systemOutTracer.print(new String(res));
			
		} else if(method.equals("java/io/PrintStream.flush()V")){
			systemOutTracer.flush();
		} else if (method.equals("java/io/FileInputStream.read()I")){
			
			int handle = frameStack.memory[frameStack.stackPointer-1];
			
//			System.err.println(new String(res));
			
			int ret = -1;
			try {
				FileInputStream input = in.get(handle);
				if(input == null){
					
					heap.objectCache.requestData(handle, 0);
					int fileStringHandle = heap.objectCache.getData();
					heap.objectCache.requestData(fileStringHandle, 0);
					int charHandle = heap.objectCache.getData();
					heap.objectCache.requestData(fileStringHandle, 2);
					int length = heap.objectCache.getData();
					heap.objectCache.requestData(fileStringHandle, 3);
					int offset = heap.objectCache.getData();
					char[] res = new char[length];
					for(int i = 0; i< length; i++){
						heap.objectCache.requestData(charHandle, i+offset);
						res[i] = (char)heap.objectCache.getData();
					}
					
					input = new FileInputStream(new String(res));
					in.put(handle, input);
				}
				ret = input.read();
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return ret;
		} else if(method.equals("java/io/FileInputStream.read([BII)I")){
			int len = frameStack.memory[frameStack.stackPointer-1];
			int offset =frameStack.memory[frameStack.stackPointer-2];
			int resultHandle = frameStack.memory[frameStack.stackPointer-3];
			int handle = frameStack.memory[frameStack.stackPointer-4];
			
			if(heapTracer.active()) {
				heap.getOHTrace().appendTrace(ObjectHeap.INVALIDATE, 0, resultHandle, offset, len, 0, 0, 0);
			}
			
			
			int ret = -1;
			try {
				FileInputStream input = in.get(handle);
				if(input == null){
					heap.objectCache.requestData(handle, 0);
					int fileStringHandle = heap.objectCache.getData();
					heap.objectCache.requestData(fileStringHandle, 0);
					int charHandle = heap.objectCache.getData();
					heap.objectCache.requestData(fileStringHandle, 2);
					int length = heap.objectCache.getData();
					char[] res = new char[length];
					for(int i = 0; i< length; i++){
						heap.objectCache.requestData(charHandle, i);
						res[i] = (char)heap.objectCache.getData();
					}
					input = new FileInputStream(new String(res));
					in.put(handle, input);
				}
				
				byte[] readValues = new byte[len+offset];
				ret = input.read(readValues, offset, len);
				

				for(int i = 0; i < ret; i++){
					heap.objectCache.writeData(resultHandle, offset+ i, readValues[i+offset]);
				}
				
				
				
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return ret;
		} else if(method.equals("java/io/File.lengthA()I")){
			int handle = frameStack.memory[frameStack.stackPointer-1];
			File input = files.get(handle);
			if(input == null){
				heap.objectCache.requestData(handle, 0);
				int fileStringHandle = heap.objectCache.getData();
				heap.objectCache.requestData(fileStringHandle, 0);
				int charHandle = heap.objectCache.getData();
				heap.objectCache.requestData(fileStringHandle, 2);
				int length = heap.objectCache.getData();
				char[] res = new char[length];
				for(int i = 0; i< length; i++){
					heap.objectCache.requestData(charHandle, i);
					res[i] = (char)heap.objectCache.getData();
				}
				input = new File(new String(res));
				files.put(handle, input);
			}
			nativeMethodLongBuffer = input.length();
			return (int)(nativeMethodLongBuffer&0xFFFFFFFF);

		} else if(method.equals("java/io/File.lengthB()I")){
			return (int)((nativeMethodLongBuffer>>32)&0xFFFFFFFF);
		} else if(method.equals("java/lang/System.arraycopyN(Ljava/lang/Object;ILjava/lang/Object;II)V")){
			
			int length = frameStack.memory[frameStack.stackPointer-1];
			int destPos = frameStack.memory[frameStack.stackPointer-2];
			int destHandle = frameStack.memory[frameStack.stackPointer-3];
			int srcPos = frameStack.memory[frameStack.stackPointer-4];
			int srcHandle = frameStack.memory[frameStack.stackPointer-5];
			if(heapTracer.active()){
				heap.getOHTrace().appendTrace(ObjectHeap.INVALIDATE, 0, destHandle, destPos, length, 0, 0, 0);
			}
			
			heap.htCache.requestData(srcHandle);
			int flags = heap.htCache.getFlags();
			if((flags & 0x8000) != 0){ // 64 Bit array
				length = 2*length; 
				srcPos = 2*srcPos;
				destPos = 2*destPos;
			}
			for(int i = 0; i < length; i++){
				heap.objectCache.requestData(srcHandle, i+srcPos);
				int data =  heap.objectCache.getData();
				heap.objectCache.writeData(destHandle, i+destPos, data);
			}
		} else if(method.equals("java/lang/Float.floatToIntBits(F)I") || method.equals("java/lang/Float.intBitsToFloat(I)F")){
				return frameStack.memory[frameStack.stackPointer-1];
		}
			
		return 0;
	}
	
	
}
