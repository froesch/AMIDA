package functionalunit.cache;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import exceptions.AmidarSimulatorException;
import functionalunit.ObjectHeap;

/**
 * Object Cache
 * @author Patrick Appenheimer
 *
 */
public class ObjectCache{	
	
	//For debugging purposes only
	//true:		tag = {handle,offset[31:3],000}
	//false:	old tag generation
	private static final boolean NEW_CACHE_ADDR = true;	
	
	private int CACHESIZE;
	private int SETS;
	private int WORDSPERLINE;
	private int BYTESPERWORD;
	private int CACHELINES;
	
	private boolean wrAlloc;	//TODO
	private boolean wrBack;		//TODO
	
	private int extMemAcc;
	
	private int[] plru;
	private CacheLine[][] cache; 
	
	private long tag;
	private int index;
	private int blockoffset;
	
	private int handle;
	private int offset;
	
	private int data;
	
//	private ObjectHeap heap;
	private Memory memory;
	private HandleTableCache htCache;
	
	private boolean synthesis;
	
	private CacheLine returnLine;
		
	
	public ObjectCache(ObjectHeap heap, Memory memory, HandleTableCache htCache, String heapConfig, boolean synthesis){
		this.configureCache(heapConfig);
		this.plru = new int[CACHELINES];
		this.cache = new CacheLine[CACHELINES][SETS];
//		this.heap = heap;
		this.memory = memory;
		this.htCache = htCache;
		this.createCacheLines();
		this.synthesis = synthesis;
//		System.out.println("====> Handle Offset: 1518 8");
//		this.generateCacheAddr(1518, 8);
//		System.out.println("====> Cache Addr: " + index +" "+blockoffset+" "+tag);
//		this.generateHandleOffset(tag, index, blockoffset, 1);
//		System.out.println("====> Handle Offset: " + handle +" "+offset);
//		System.out.println("====> Handle Offset: 3037 2");
//		this.generateCacheAddr(3037, 2);
//		System.out.println("====> Cache Addr: " + index +" "+blockoffset+" "+tag);
//		this.generateHandleOffset(tag, index, blockoffset, 0);
//		System.out.println("====> Handle Offset: " + handle +" "+offset);
	}
	
	private void createCacheLines(){
		for(int i = 0; i < SETS; i++){
			for(int j = 0; j < CACHELINES; j++){
				cache[j][i] = new CacheLine(WORDSPERLINE);
			}
		}
	}
	
	private void configureCache(String heapConfig){
		if(heapConfig == null) System.err.println("No Heap Config File");
		JSONParser parser = new JSONParser();
		FileReader fileReader;
		JSONObject json = null;
		try {
			fileReader = new FileReader(heapConfig);
			json = (JSONObject) parser.parse(fileReader);
			String cacheConfig = (String) json.get("CacheConfig");
			if(cacheConfig == null) System.err.println("No Cache Config File");
			fileReader = new FileReader(cacheConfig);
			json = (JSONObject) parser.parse(fileReader);
		} catch (FileNotFoundException e) {
			System.err.println("No Config File found");
			e.printStackTrace();
		} catch (IOException e) {
			System.err.println("Error while reading config file");
			e.printStackTrace();
		} catch (ParseException e) {
			System.err.println("Error while reading config file");
			e.printStackTrace();
		}
		
		long size = (long) json.get("size");
		CACHESIZE = ((int) size) * 1024;
		long sets = (long) json.get("sets");
		SETS = (int) sets;
		long wordsperline = (long) json.get("wordsperline");
		WORDSPERLINE = (int) wordsperline;
		BYTESPERWORD = 4;
		CACHELINES = (CACHESIZE/SETS)/(BYTESPERWORD*WORDSPERLINE);
		wrAlloc = (boolean) json.get("wrAlloc");
		wrBack = (boolean) json.get("wrBack");
		long extMem = (long) json.get("extMemoryAccTicks");
		extMemAcc = (int) extMem; 	}
	
	private int generateCacheAddr(int handle, int offset){
		int selMask = 99;
		if(NEW_CACHE_ADDR) selMask = generateCacheAddrNew(handle, offset);
		if(!NEW_CACHE_ADDR) selMask = generateCacheAddrOld(handle, offset);
//		System.out.println("GenerateCacheAddr: Handle="+Integer.toBinaryString(handle)+" Offset="+Integer.toBinaryString(offset)+" Tag="+Long.toBinaryString(tag));
		return selMask;
	}	
	
	private void generateHandleOffset(long tag, int index, int boff, int selMask){
		if(NEW_CACHE_ADDR) generateHandleOffsetNew(tag, boff);
		if(!NEW_CACHE_ADDR) generateHandleOffsetOld(tag, index, boff, selMask);
//		System.out.println("generateHandleOffset: Tag="+Long.toBinaryString(tag)+" Handle="+Integer.toBinaryString(handle)+" Offset="+Integer.toBinaryString(offset));
	}
	
	private long getTag(int index, int set){
//		long tag51to20 = cache[index*10][set];
//		long tag19to0andFlags = cache[index*10+1][set];
//		long tag19to0 = tag19to0andFlags & 0xFFFFF000;
//		return (tag51to20 << 20) + (tag19to0 >>> 12);
		return cache[index][set].getTag();
	}
	
	private int getValidBit(int index, int set){
//		int tag19to0andFlags = cache[index*10+1][set];
//		int shiftedFlags = tag19to0andFlags >>> 11;
//		return shiftedFlags & 0x00000001;
		return cache[index][set].getValidBit();
	}
	
	private void setOverhead(int index, int set, long tag, int validBit, int modBit, int maxOffset, int selMask){
//		int tag19to0 = (int)(tag & 0xFFFFF);
//		int tag51to20 = (int)(tag >>> 20);
//		int tag19to0andFlags = (tag19to0 << 1) | validBit;
//		tag19to0andFlags = (tag19to0andFlags << 1) | modBit;
//		tag19to0andFlags = (tag19to0andFlags << 3) | maxOffset;
//		tag19to0andFlags = (tag19to0andFlags << 2) | selMask;
//		tag19to0andFlags = tag19to0andFlags << 5;
//		cache[index*10][set] = tag51to20;
//		cache[index*10+1][set] = tag19to0andFlags;
		cache[index][set].setOverhead(tag, validBit, modBit, maxOffset, selMask);
	}
	/*
	private void setValidBit(int index, int set, int flag){
		int tag19to0andFlags = cache[index*10+1][set];
		if(flag == 0) tag19to0andFlags = tag19to0andFlags & 0xFFFFFBFF;
		if(flag == 1) tag19to0andFlags = tag19to0andFlags | 0x00000400;
		cache[index*10+1][set] = tag19to0andFlags;
	}
	*/
	private int getModifiedBit(int index, int set){
//		int tag19to0andFlags = cache[index*10+1][set];
//		int shiftedFlags = tag19to0andFlags >>> 10;
//		return shiftedFlags & 0x00000001;
		return cache[index][set].getModBit();
	}
	
	private int getMaxOffset(int index, int set){
//		int tag19to0andFlags = cache[index*10+1][set];
//		int shiftedFlags = tag19to0andFlags >>> 7;
//		return shiftedFlags & 0x00000007;
		return cache[index][set].getMaxOffset();
	}
	
	private int getSelectionMask(int index, int set){
//		int tag19to0andFlags = cache[index*10+1][set];
//		int shiftedFlags = tag19to0andFlags >>> 5;
//		return shiftedFlags & 0x00000003;
		return cache[index][set].getSelMask();
	}
	
	public int requestData(int handle, int offset) {
		int selMask = generateCacheAddr(handle, offset);
		if(selMask == 99) throw new AmidarSimulatorException("ObjCache.requestData() says: \"No valid selMask!\"");
		for(int i = 0; i<SETS; i++){
			if(getTag(index, i) == tag && getValidBit(index, i) != 0){
				data = cache[index][i].getData(blockoffset);
				setPLRU(index, i);
				return 0;
			}
		}
		int ticks = 0;
		int replaceInSet = decisionPLRU(index);
		if(replaceInSet == 99) throw new AmidarSimulatorException("Something went wrong with the PLRU decision");
		else{
			if(this.getModifiedBit(index, replaceInSet) == 1){
				this.generateHandleOffset(this.getTag(index, replaceInSet), index, 0, this.getSelectionMask(index, replaceInSet));
				int htTicks = htCache.requestData(this.handle);
				int writeAddrHT = htCache.getAddr();
				int writeAddr = writeAddrHT + this.offset;
				int writeBackTicks = extMemAcc + 6;
				for(int i=0; i<=this.getMaxOffset(index, replaceInSet); i++){
					memory.write(writeAddr+i, cache[index][replaceInSet].getData(i));
					writeBackTicks++;
				}
				if(writeBackTicks > htTicks) ticks = ticks + writeBackTicks;
				else ticks = ticks + htTicks;
			}
			int htTicks = htCache.requestData(handle);
			int htAddr = htCache.getAddr();
			int phyAddr = htAddr + offset;
			setOverhead(index, replaceInSet, tag, 1, 0, 0, selMask);
			int reloadTicks = extMemAcc + 4;
			for(int i=0; i<8; i++){
				cache[index][replaceInSet].setData(i, memory.read(phyAddr-blockoffset+i));
				reloadTicks++;
			}
			if(reloadTicks > htTicks) ticks = ticks + reloadTicks;
			else ticks = ticks + htTicks;
		}
		data = cache[index][replaceInSet].getData(blockoffset);
		setPLRU(index, replaceInSet);		
		return ticks-1;
	}

	public int writeData(int handle, int offset, int data){
		int selMask = generateCacheAddr(handle, offset);
		if(selMask == 99) throw new AmidarSimulatorException("ObjCache.writeData() says: \"No valid selMask!\"");
		//System.out.println("|||Cache Write||| Index: " + index + " / Tag: " + tag + " / Blockoffset: " + blockoffset);
		for(int i = 0; i<SETS; i++){
			if(getTag(index, i) == tag && getValidBit(index, i) != 0){
				//System.out.println("|||    HIT    |||");
				cache[index][i].setData(blockoffset, data);
				int oldMaxOffset = getMaxOffset(index, i);
				if(oldMaxOffset > blockoffset) setOverhead(index, i, tag, 1, 1, oldMaxOffset, selMask); //this.getSelectionMask(index, i));
				else setOverhead(index, i, tag, 1, 1, blockoffset, selMask); //this.getSelectionMask(index, i));
				setPLRU(index, i);
				return 0;
			}
		}
		//System.out.println("|||    MISS   |||");
		int ticks = 0;
		int replaceInSet = decisionPLRU(index);
		if(replaceInSet == 99) throw new AmidarSimulatorException("Something went wrong with the PLRU decision");
		else{
			if(this.getModifiedBit(index, replaceInSet) == 1){
				//System.out.println("||| Write Back|||");
				this.generateHandleOffset(this.getTag(index, replaceInSet), index, 0, this.getSelectionMask(index, replaceInSet));
				int htTicks = htCache.requestData(this.handle);
				int writeAddrHT = htCache.getAddr();
				int writeAddr = writeAddrHT + this.offset;
				int writeBackTicks = extMemAcc + 6;
				for(int i=0; i<=this.getMaxOffset(index, replaceInSet); i++){
					memory.write(writeAddr+i, cache[index][replaceInSet].getData(i));
					writeBackTicks++;
				}
				if(writeBackTicks > htTicks) ticks = ticks + writeBackTicks;
				else ticks = ticks + htTicks;
			}
			int htTicks = htCache.requestData(handle);
			int htAddr = htCache.getAddr();
			int phyAddr = htAddr + offset;
			int reloadTicks = extMemAcc + 4;
			for(int i=0; i<8; i++){
				cache[index][replaceInSet].setData(i, memory.read(phyAddr-blockoffset+i));
				reloadTicks++;
			}
			if(reloadTicks > htTicks) ticks = ticks + reloadTicks;
			else ticks = ticks + htTicks;
		}
		setOverhead(index, replaceInSet, tag, 1, 1, blockoffset, selMask);
		cache[index][replaceInSet].setData(blockoffset, data);
		setPLRU(index, replaceInSet);		
		return ticks-1;
	}
	
	private void setPLRU(int index, int set){
		switch(set){
		case 0:
			plru[index] = plru[index] | 0x6;
			break;
		case 1:
			plru[index] = plru[index] | 0x4;
			plru[index] = plru[index] & 0x5;
			break;
		case 2:
			plru[index] = plru[index] | 0x1;
			plru[index] = plru[index] & 0x3;
			break;
		case 3:
			plru[index] = plru[index] & 0x2;
			break;
		default:		
		}
	}
	
	private int decisionPLRU(int index){
		if(cache[index][0].getValidBit() == 0) return 0;
		if(cache[index][1].getValidBit() == 0) return 1;
		if(cache[index][2].getValidBit() == 0) return 2;
		if(cache[index][3].getValidBit() == 0) return 3;
		
		switch(plru[index]){
		case 0:
		case 1:
			return 0;
		case 2:
		case 3:
			return 1;
		case 4:
		case 6:
			return 2;
		case 5:
		case 7:
			return 3;
		default:
			return 99;
		}
	}
	
	public int getData() {
		return data;
	}

	public boolean isReady() {
		return true;
	}

	public void invalidate() {

	}
	
	public int getMemoryAccessTime(){
		return extMemAcc;
	}
	
	private int generateCacheAddrOld(int handle, int offset){
		int selMask = 99;
		if(offset<=7){
			blockoffset = offset & 0x7;			
			int handle8to0 = handle & 0x1FF;
			index = handle8to0;	
			long offset31to3 = offset & 0xFFFFFFF8;
			long handle31to9 = handle & 0xFFFFFE00;
			tag = (offset31to3 << 20) + (handle31to9 >>> 9);
			selMask = 0;
		}		
		if(8<=offset && offset<=15){
			blockoffset = offset & 0x7;			
			int handle7to0 = handle & 0xFF;
			int offset3 = offset & 0x8;
			index = (handle7to0 << 1) + (offset3 >>> 3);
			long offset31to4 = offset & 0xFFFFFFF0;
			long handle31to8 = handle & 0xFFFFFF00;
			tag = (offset31to4 << 20) + (handle31to8 >>> 8);
			selMask = 1;
		}		
		if(16<=offset && offset<=31){
			blockoffset = offset & 0x7;			
			int handle6to0 = handle & 0x7F;
			int offset4to3 = offset & 0x18;
			index = (handle6to0 << 2) + (offset4to3 >>> 3);	
			long offset31to5 = offset & 0xFFFFFFE0;
			long handle31to7 = handle & 0xFFFFFF80;
			tag = (offset31to5 << 20) + (handle31to7 >>> 7);
			selMask = 2;
		}		
		if(32<=offset){
			blockoffset = offset & 0x7;			
			int handle5to0 = handle & 0x3F;
			int offset5to3 = offset & 0x38;
			index = (handle5to0 << 3) + (offset5to3 >>> 3);		
			long offset31to6 = offset & 0xFFFFFFC0;
			long handle31to6 = handle & 0xFFFFFFC0;
			tag = (offset31to6 << 20) + (handle31to6 >>> 6);
			selMask = 3;
		}
		return selMask;
	}
	
	private int generateCacheAddrNew(int handle, int offset){
		int selMask = 99;
		if(offset<=7){
			blockoffset = offset & 0x7;			
			int handle8to0 = handle & 0x1FF;
			index = handle8to0;	
			long longHandle = handle;
			int tmpOffset = offset & 0xFFFFFFF8;
			tag = (longHandle << 32) + tmpOffset;
			selMask = 0;
		}		
		if(8<=offset && offset<=15){
			blockoffset = offset & 0x7;			
			int handle7to0 = handle & 0xFF;
			int offset3 = offset & 0x8;
			index = (handle7to0 << 1) + (offset3 >>> 3);
			long longHandle = handle;
			int tmpOffset = offset & 0xFFFFFFF8;
			tag = (longHandle << 32) + tmpOffset;
			selMask = 1;
		}		
		if(16<=offset && offset<=31){
			blockoffset = offset & 0x7;			
			int handle6to0 = handle & 0x7F;
			int offset4to3 = offset & 0x18;
			index = (handle6to0 << 2) + (offset4to3 >>> 3);	
			long longHandle = handle;
			int tmpOffset = offset & 0xFFFFFFF8;
			tag = (longHandle << 32) + tmpOffset;
			selMask = 2;
		}		
		if(32<=offset){
			blockoffset = offset & 0x7;			
			int handle5to0 = handle & 0x3F;
			int offset5to3 = offset & 0x38;
			index = (handle5to0 << 3) + (offset5to3 >>> 3);		
			long longHandle = handle;
			int tmpOffset = offset & 0xFFFFFFF8;
			tag = (longHandle << 32) + tmpOffset;
			selMask = 3;
		}
		return selMask;
	}

	private void generateHandleOffsetOld(long tag, int index, int blockoffset, int selMask){
		switch(selMask){
		case 0:
			int handle31to9 = (int)(tag & 0x7FFFFF);
			int offset31to3 = (int)(tag >>> 23);
			int handle8to0 = index;
			handle = (handle31to9 << 9) + handle8to0;
			offset = (offset31to3 << 3) + blockoffset;
			break;
		case 1:
			int handle31to8 = (int)(tag & 0xFFFFFF);
			int offset31to4 = (int)(tag >>> 24);
			int offset3 = index & 0x1;
			int handle7to0 = index >>> 1;
			handle = (handle31to8 << 8) + handle7to0;
			offset = (offset31to4 << 4) + (offset3 << 3) + blockoffset;
			break;
		case 2:
			int handle31to7 = (int)(tag & 0x1FFFFFF);
			int offset31to5 = (int)(tag >>> 25);
			int offset4to3 = index & 0x3;
			int handle6to0 = index >>> 2;
			handle = (handle31to7 << 7) + handle6to0;
			offset = (offset31to5 << 5) + (offset4to3 << 3) + blockoffset;
			break;
		case 3:
			int handle31to6 = (int)(tag & 0x3FFFFFF);
			int offset31to6 = (int)(tag >>> 26);
			int offset5to3 = index & 0x7;
			int handle5to0 = index >>> 3;
			handle = (handle31to6 << 6) + handle5to0;
			offset = (offset31to6 << 6) + (offset5to3 << 3) + blockoffset;
			break;
		default: throw new AmidarSimulatorException("generateHandleOffset() says: \"No valid selMask!\"");
		}
	}
	
	private void generateHandleOffsetNew(long tag, int boff){
		handle = (int) (tag >>> 32);
		offset = (int) (tag+boff);
	}
	
}
