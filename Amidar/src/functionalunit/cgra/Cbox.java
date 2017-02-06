package functionalunit.cgra;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import cgramodel.ContextMaskCBox;
import exceptions.AmidarSimulatorException;

/**
 * The CBox is a controlbox used to evaluate branch and loop conditions.  
 * @author Dennis Wolf
 *
 */
public class Cbox {

	
	/**
	 * inputs. Corresponds to the number of PEs
	 */
	public boolean[] Input;
	
	
	/**
	 *  The inputmapping determines which PE is connected to which slot 
	 */
	public int[] inputmapping;
	
	
	/**
	 * Input slot for the enable signal
	 */
	public boolean InputEnable;

	
	/**
	 * Memory
	 */
	public boolean[] regfile;

	
	/**
	 * Helper signal
	 */
	private boolean regB1,regB2,regA,regInA,regInB, inputReg;

	
	/**
	 * Current Context
	 */
	public long context;

	/**
	 * The contextmask determines how the avaiable context is to be interpreted
	 */
	public ContextMaskCBox contextmask;

	
	/**
	 * Constructor
	 */
	public Cbox(){
		
	}
	
	
	/**
	 * Configurates the CBox
	 */
	public void configure(int nrOfPEs, int numberOfPEscontrolflow, int max_branches, ContextMaskCBox mask){
		Input = new boolean[numberOfPEscontrolflow];
		inputmapping = new int[nrOfPEs];
		regfile = new boolean[max_branches];
		contextmask = mask;
	}
	
	
	/**
	 * Setter method for the inputmapping of the status signals
	 */
	public void setInputMapping(int slot, int peId){
		inputmapping[peId] = slot;
	}

	/**
	 * Setter method for an status input
	 */
	protected void setInput(int pe, boolean value){
		Input[pe] = value;
	}

	
	/**
	 * Emulates the multiplexor for the inputs
	 */
	protected void muxInputs(){
		if(Input.length >1){
			inputReg = Input[contextmask.mux(context)];
		}
		else{
			inputReg = Input[0];
		}
	}

	
	/**
	 * Method to load a new context 
	 */
	public void fetchContext(long context) {
		this.context = context;		
	}

	
	/**
	 * operates memory writes since they are clocked 
	 */
	public void operateClocked(){
		if(contextmask.writeEnable(context)){
			regfile[contextmask.writeAddressA(context)] = regInA;
			regfile[contextmask.writeAddressB(context)] = regInB;
		}
	}

	
	/**
	 * Triggers the emulation of the combinatorial circuit
	 */
	public void operateComb(){
		
		regB1 = regfile[contextmask.readAddressB1(context)];
		regB2 = regfile[contextmask.readAddressB2(context)];
		regA = regfile[contextmask.readAddressA(context)];
		muxInputs();
		regInA = inputReg;		// tracks the branch as it is
		regInB = !inputReg;		// takes the second branch, which is always the negation
		if(!contextmask.bypassAAnd(context))
			regInA = regInA && regA;
		if(!contextmask.bypassAOr(context))
			regInA = regInA || regB1;	

		if(!contextmask.bypassBAnd(context))
			regInB = regInB && regA;
		if(!contextmask.bypassBOr(context))
			regInB = regInB || regB2;
	}

	/**
	 * Return the output of CBOX 
	 */
	public boolean getOutputControlUnit() {
		// 0 -> direct A
		// 1 -> direct B
		// 2 -> RegA
		// 3 -> RegB
		switch(contextmask.outMux(context)) {
		case 0: return regInA;
		case 1: return regInB;
		case 2: return regA;
		case 3: return regB1;
		default: throw new AmidarSimulatorException("PBOX Context: NOT A VALID MUX INPUT. NUMBER IS NEGATIV OR TOO HIGH");
		}
	}

	/**
	 * Returns the Predication output
	 * @return
	 */
	public boolean getPredicationOutput(){
		return regB1;
	}

	/**
	 * Setter method for the enable input
	 */
	public void setEnable(boolean enable) {
		InputEnable = enable;
	}	

	/**
	 * Getter method for the enable input
	 */
	public boolean getInputEnable() {
		return InputEnable;
	}

}
