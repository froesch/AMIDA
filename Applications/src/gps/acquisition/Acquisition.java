package gps.acquisition;


public class Acquisition {
	/*//to much overhead
	private class complex {

		complex(float real, float imag){


		}
	}
	 */
	//parameters
	final private int N; 
	final private int f_s; 
	final private int f_step; 
	final private int f_max; 
	final private int f_min; 
	final private float gamma; 
	//derived parameters
	final int m;
	int [] f_d;

	//L1 C/A Codes
	private int C_index = 0; //just to help us with the array :D 
	private float [] C_r = null;
	private float [] C_i = null;

	private int X_in_index = 0; //just to help us with the array :D 
	private float [] X_in_r = null;
	private float [] X_in_i = null;

	private float omega  = 0.015707964F;
	private float factor = 1.5707963E-5F;

	private int dopplerVerschiebung;
	private int codeVerschiebung;

	public Acquisition(){
		this.N=400;
		this.f_s=400000;
		this.f_step=1000;
		this.f_max=5000;
		this.f_min=-5000;
		this.gamma= 0.015F;
		this.X_in_r = new float[this.N];
		this.X_in_i = new float[this.N];
		this.C_r = new float[this.N];
		this.C_i = new float[this.N];


		this.m=1+(this.f_max-this.f_min)/this.f_step;
		this.f_d= new int[m];
		for(int n=0;n<m;n++){
			this.f_d[n]=this.f_min+n*this.f_step;
		}
	}

	//we override this for easy access
	public Acquisition(int N,int f_s, int f_step, int f_max, int f_min, float gamma){
		this.N=N;
		this.f_s=f_s;
		this.f_step=f_step;
		this.f_max=f_max;
		this.f_min=f_min;
		this.gamma=gamma;
		this.X_in_r = new float[this.N];
		this.X_in_i = new float[this.N];
		this.C_r = new float[this.N];
		this.C_i = new float[this.N];

		this.m=1+(this.f_max-this.f_min)/this.f_step;
		this.f_d= new int[m];
		for(int n=0;n<m;n++){
			this.f_d[n]=this.f_min+n*this.f_step;
		}
	}



	public boolean enterSample(float real, float imag){
		//System.out.println("enter Sample !");
		this.X_in_r[this.X_in_index]=real;
		this.X_in_i[this.X_in_index]=imag;
		this.X_in_index++;
		if(this.N==this.X_in_index){
			this.X_in_index=0; //zurück setzen
			return true;	
		}
		return false;
	}

	public boolean enterCode(float real, float imag){ //L1 C/A Codes
		//System.out.println("enter Code !");
		this.C_r[this.C_index]=real;
		this.C_i[this.C_index]=imag;
		C_index++;
		if(this.N==this.C_index){
			this.C_index=0; //zurück setzen
			return true;	
		}
		return false;
	}

	private void DFT(float [] x_r, float [] x_i, float [] X_DFT_r, float [] X_DFT_i){
		int N = x_r.length;
		//		float omega=(float) (2*Math.PI/N);
		//		System.out.println("omega = "+ omega);
		// float[] X_DFT_r = new float[N];
		// float[] X_DFT_i = new float[N];
		float real_sum;
		float imag_sum;
		for(int k=0;k<N;k++){
			real_sum = 0;
			imag_sum = 0;
			for(int n=0;n<N;n++){
				real_sum=(float) (real_sum + (x_r[n]*Math.cos(omega*n*k)) + (x_i[n]*Math.sin(omega*n*k)));
				imag_sum=(float) (imag_sum + (x_i[n]*Math.cos(omega*n*k)) - (x_r[n]*Math.sin(omega*n*k)));
			}
			X_DFT_r[k]=real_sum;
			X_DFT_i[k]=imag_sum;
		}
	}

	private void IDFT(float [] X_DFT_r, float [] X_DFT_i, float [] x_r, float [] x_i){
		int N = X_DFT_r.length;
		//		float omega=(float) (2*Math.PI/N);
		float real_sum=0;
		float imag_sum=0;
		for(int n=0;n<N;n++){
			real_sum=0;
			imag_sum=0;
			for(int k=0;k<N;k++){
				real_sum=(float) (real_sum + (X_DFT_r[k]*Math.cos(omega*n*k)) - (X_DFT_i[k]*Math.sin(omega*n*k)));
				imag_sum=(float) (imag_sum + (X_DFT_r[k]*Math.sin(omega*n*k)) + (X_DFT_i[k]*Math.cos(omega*n*k)));
			}
			x_r[n]=real_sum/(this.N); // x_r[n]=real_sum/(this.N*this.N);
			x_i[n]=imag_sum/(this.N); // x_i[n]=imag_sum/(this.N*this.N);
		}
	}



	public boolean startAcquisition(){

		float [] X_fd_r = new float[this.N]; //0 ... N-1
		float [] X_fd_i = new float[this.N];
		//  X_in[n]*e^-(i*n*2*pi*(f_d/f_s)) 
		//= X_in[n]*(cos(n*2*pi*(f_d/f_s))+i*sin(n*2*pi*(f_d*n/f_s)))
		//= X_in[n][1]*cos(n*2*pi*(f_d/f_s)+X_in[n][2]*sin(n*2*pi*(f_d*n/f_s)))
		float[] C_DFT_r= new float [this.N];
		float[] C_DFT_i= new float [this.N];
		float[] X_fd_DFT_r= new float [this.N];
		float[] X_fd_DFT_i= new float [this.N];

		float[] product_r = new float[this.N];
		float[] product_i = new float[this.N];

		float [][] R_fd_r = new float[this.m][this.N]; //0 ... N-1
		float [][] R_fd_i = new float[this.m][this.N];

		DFT(C_r, C_i, C_DFT_r, C_DFT_i);


		float S_max = 0;
		float res = 0;

		//		float factor = (float) (2*Math.PI/f_s);
		//		System.out.println("factor = " + factor);
		for(int f=0;f<this.m;f++){ //freqenzen
			for(int n=0;n<this.N;n++){
				//	System.out.println("Test");
				//f_d=this.f_min+f*this.f_step
				X_fd_r[n]=(float) (X_in_r[n]*Math.cos(n*factor*this.f_d[f])+X_in_i[n]*Math.sin(n*factor*this.f_d[f]));
				X_fd_i[n]=(float) (-X_in_r[n]*Math.sin(n*factor*this.f_d[f])+X_in_i[n]*Math.cos(n*factor*this.f_d[f]));
			}

			DFT(X_fd_r,X_fd_i, X_fd_DFT_r, X_fd_DFT_i);

			for(int n=0;n<this.N;n++){
				product_r[n] = X_fd_DFT_r[n]*C_DFT_r[n] + X_fd_DFT_i[n]*C_DFT_i[n];
				product_i[n] = X_fd_DFT_i[n]*C_DFT_r[n] - X_fd_DFT_r[n]*C_DFT_i[n];
			}

			IDFT(product_r, product_i, R_fd_r[f], R_fd_i[f]);

			for(int n=0;n<this.N;n++){ //sample
				//max R(f,t)
				res=(R_fd_r[f][n]*R_fd_r[f][n])+(R_fd_i[f][n]*R_fd_i[f][n]);
				if(res>=S_max){
					S_max=res;
					//	System.out.println("new max found: "+S_max+", frequenz: "+this.f_d[f]+" Codeverschiebung: "+ n);
					this.codeVerschiebung=n;
					this.dopplerVerschiebung=this.f_d[f];
				}
			}

		}



		


		float P_in=0;
		for(int n=0;n<this.N;n++){
			P_in=P_in+X_in_r[n]*X_in_r[n]+X_in_i[n]*X_in_i[n];	
		}
		P_in=P_in/this.N;



		if(S_max/P_in>this.gamma){
			return true;
		}else{
			return false;
		} 

		/*

		this.dopplerVerschiebung = 3000;
		this.codeVerschiebung = 306;
		return true; 
		 */
	} 

	public int getDopplerverschiebung(){
		return this.dopplerVerschiebung;
	}

	public int getCodeVerschiebung(){
		return this.codeVerschiebung;
	}



}
