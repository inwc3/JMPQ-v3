package de.peeeq.jmpq;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;

import com.google.common.io.LittleEndianDataInputStream;

import de.peeeq.jmpq.BlockTable.Block;

/**
 * @author peq & Crigges Some basic basic pure java based mpq implementation to
 *         open and modify warcraft 3 archives. Any bugs report here:
 *         https://github.com/Crigges/JMpq-v2/issues/new
 */
public class JMpqEditor {
	private FileChannel fc;
	private File mpq;
	private int headerOffset = -1;
	// Header
	private int headerSize;
	private int archiveSize;
	private int formatVersion;
	private int discBlockSize;
	private int hashPos;
	private int blockPos;
	private int hashSize;
	private int blockSize;

	private HashTable hashTable;
	private BlockTable blockTable;
	private Listfile listFile;
	private HashMap<File, String> internalFilename = new HashMap<>();
	private HashMap<Block, String> blockToFilename = new HashMap<>();
	private boolean useBestCompression = false;
	
	//BuildData
	private ArrayList<File> filesToAdd = new ArrayList<>();
	private boolean keepHeaderOffset = true;
	private int newHeaderSize;
	private int newArchiveSize;
	private int newFormatVersion;
	private int newDiscBlockSize;
	private int newHashPos;
	private int newBlockPos;
	private int newHashSize;
	private int newBlockSize;
	
	/**
	 * Creates a new editor by parsing an exisiting mpq.
	 * 
	 * @param mpq
	 *            the mpq to parse
	 * @throws JMpqException
	 *             if mpq is damaged or not supported
	 * @throws IOException
	 *             if access problems occcur
	 */
	public JMpqEditor(File mpq) throws JMpqException {
		this.mpq = mpq;
		try {
			fc = FileChannel.open(mpq.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ);
			
			headerOffset = searchHeader();
			
			MappedByteBuffer temp = fc.map(MapMode.READ_ONLY, headerOffset + 4, 4);
			temp.order(ByteOrder.LITTLE_ENDIAN);
			headerSize = temp.getInt();
			
			MappedByteBuffer headerBuffer = fc.map(MapMode.READ_ONLY, headerOffset + 8, headerSize);
			headerBuffer.order(ByteOrder.LITTLE_ENDIAN);
			readHeader(headerBuffer);
			
			MappedByteBuffer hashBuffer = fc.map(MapMode.READ_ONLY, hashPos + headerOffset, hashSize * 16);
			hashBuffer.order(ByteOrder.LITTLE_ENDIAN);
			hashTable = new HashTable(hashBuffer);
			
			MappedByteBuffer blockBuffer = fc.map(MapMode.READ_ONLY, blockPos + headerOffset, blockSize * 16);
			blockBuffer.order(ByteOrder.LITTLE_ENDIAN);
			blockTable = new BlockTable(blockBuffer);
			
			if(hasFile("(listfile)")){
				File tempFile = File.createTempFile("list", "file");
				extractFile("(listfile)", tempFile);
				listFile = new Listfile(Files.readAllBytes(tempFile.toPath()));
			}
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}
	
	private int searchHeader() throws JMpqException{
		try {
			MappedByteBuffer buffer = fc.map(MapMode.READ_ONLY, 0, (fc.size() / 512) * 512);
			buffer.order(ByteOrder.LITTLE_ENDIAN);
			for(int i = 0; i < (fc.size() / 512); i++){
				buffer.position(i * 512);
				byte[] start = new byte[3];
				buffer.get(start);
				String s =  new String(start);
				if(s.equals("MPQ")){
					return buffer.position() - 3;
				}
			}
			throw new JMpqException("The given file is not a mpq or damaged");
		} catch (IOException e) {
			throw new JMpqException(e);
		}
	}
	
	private void readHeader(MappedByteBuffer buffer){
		archiveSize = buffer.getInt();
		formatVersion = buffer.getShort();
		discBlockSize = 512 * (1 << buffer.getShort());
		hashPos = buffer.getInt();
		blockPos = buffer.getInt();
		hashSize = buffer.getInt();
		blockSize = buffer.getInt();
	}
	
	private void writeHeader(MappedByteBuffer buffer){
		buffer.putInt(newHeaderSize);
		buffer.putInt(newArchiveSize);
		buffer.putShort((short) newFormatVersion);
		buffer.putShort((short) 3);
		buffer.putInt(newHashPos);
		buffer.putInt(newBlockPos);
		buffer.putInt(newHashSize);
		buffer.putInt(newBlockSize);
	}
	
	public void close() throws IOException{
		File temp = File.createTempFile("crig", "mpq");
		FileChannel writeChannel = FileChannel.open(temp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
		
		if(keepHeaderOffset){
			MappedByteBuffer headerReader = fc.map(MapMode.READ_ONLY, 0, headerOffset + 4);
			writeChannel.write(headerReader);
		}
		
		newHeaderSize = headerSize;
		newFormatVersion = formatVersion;
		newDiscBlockSize = discBlockSize;
		calcNewTableSize();
		newHashPos = headerSize + 8;
		newBlockPos = headerSize + 8 + newHashSize * 16;
		
		ArrayList<Block> newBlocks = new ArrayList<>();
		ArrayList<String> newFiles = new ArrayList<>();
		LinkedList<String> remainingFiles = (LinkedList<String>) listFile.getFiles().clone();
		int currentPos = headerOffset + headerSize + 8 + newHashSize * 16 + newBlockSize * 16;
		for(File f : filesToAdd){
			newFiles.add(internalFilename.get(f));
			remainingFiles.remove(internalFilename.get(f));
			MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, f.length());
			Block newBlock = new Block(currentPos - headerOffset, 0, 0, 0);
			newBlocks.add(newBlock);
			MpqFile.writeFileAndBlock(f, newBlock, fileWriter, newDiscBlockSize);
			currentPos += newBlock.getCompressedSize();
		}
		for(String s : remainingFiles){
			newFiles.add(s);
			int pos = hashTable.getBlockIndexOfFile(s);
			Block b = blockTable.getBlockAtPos(pos);
			MappedByteBuffer buf = fc.map(MapMode.READ_ONLY, headerOffset, fc.size() - headerOffset);
			buf.order(ByteOrder.LITTLE_ENDIAN);
			MpqFile f = new MpqFile(buf , b, discBlockSize, s);
			MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, b.getCompressedSize());
			Block newBlock = new Block(currentPos - headerOffset, 0, 0, 0);
			newBlocks.add(newBlock);
			f.writeFileAndBlock(newBlock, fileWriter);
			currentPos += b.getCompressedSize();
		}
		
		newFiles.add("(listfile)");
		byte[] listfileArr = listFile.asByteArray();
		MappedByteBuffer fileWriter = writeChannel.map(MapMode.READ_WRITE, currentPos, listfileArr.length);
		Block newBlock = new Block(currentPos - headerOffset, 0, 0, 0);
		newBlocks.add(newBlock);
		MpqFile.writeFileAndBlock(listfileArr, newBlock, fileWriter, newDiscBlockSize);
		currentPos += newBlock.getCompressedSize();
		
		newArchiveSize = currentPos;
		
		MappedByteBuffer hashtableWriter = writeChannel.map(MapMode.READ_WRITE, headerOffset + newHashPos, newHashSize * 16);
		hashtableWriter.order(ByteOrder.LITTLE_ENDIAN);
		HashTable.writeNewHashTable(newHashSize, newFiles, hashtableWriter);
		
		MappedByteBuffer blocktableWriter = writeChannel.map(MapMode.READ_WRITE, headerOffset + newBlockPos, newBlockSize * 16);
		blocktableWriter.order(ByteOrder.LITTLE_ENDIAN);
		BlockTable.writeNewBlocktable(newBlocks, newBlockSize, blocktableWriter);
		
		MappedByteBuffer headerWriter = writeChannel.map(MapMode.READ_WRITE, headerOffset + 4, headerSize + 4);
		headerWriter.order(ByteOrder.LITTLE_ENDIAN);
		writeHeader(headerWriter);
		
		fc.close();
		writeChannel.close();
		FileInputStream in = new FileInputStream(temp);
		FileOutputStream out = new FileOutputStream(mpq);
		for(int i = 1; i < newArchiveSize; i++){
			out.write(in.read());
		}
	}
	
	private void calcNewTableSize(){
		int target = listFile.getFiles().size() + 1;
		int current = 2;
		while(current < target){
			current *= 2;
		}
		newHashSize = current;
		newBlockSize = listFile.getFiles().size() + 1;
	}
	
	
	public void printHeader(){
		System.out.println("Header offset: " + headerOffset);
		System.out.println("Archive size: " + archiveSize);
		System.out.println("Format version: " + formatVersion);
		System.out.println("Disc block size: " + discBlockSize);
		System.out.println("Hashtable position: " + hashPos);
		System.out.println("Blocktable position: " + blockPos);
		System.out.println("Hashtable size: " + hashSize);
		System.out.println("Blocktable size: " + blockSize);
	}
	
	public void extractAllFiles(File dest) throws JMpqException {
		if(!dest.isDirectory()){
			throw new JMpqException("Destination location isn't a directory");
		}
		if(listFile != null){
			for(String s : listFile.getFiles()){
				File temp = new File(dest.getAbsolutePath() + "\\" + s);
				temp.getParentFile().mkdirs();
				extractFile(s, temp);
			}
		}else{
			ArrayList<Block> blocks = blockTable.getAllVaildBlocks();
			try{
				int i = 0;
				for(Block b : blocks){
					if((b.getFlags() & MpqFile.ENCRYPTED) == MpqFile.ENCRYPTED){
						continue;
					}
					MappedByteBuffer buf = fc.map(MapMode.READ_ONLY, headerOffset, fc.size() - headerOffset);
					buf.order(ByteOrder.LITTLE_ENDIAN);
					MpqFile f = new MpqFile(buf , b, discBlockSize, "");
					f.extractToFile(new File(dest.getAbsolutePath() + "\\" + i));
					i++;
				}
			}catch (IOException e) {
				throw new JMpqException(e);
			}
		}
	}
	
	public int getTotalFileCount() throws JMpqException{
		return blockTable.getAllVaildBlocks().size();
	}

	/**
	 * Extracts the specified file out of the mpq
	 * 
	 * @param name
	 *            of the file
	 * @param dest
	 *            to that the files content get copyed
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public void extractFile(String name, File dest) throws JMpqException {
		try {
			int pos = hashTable.getBlockIndexOfFile(name);
			Block b = blockTable.getBlockAtPos(pos);
			MappedByteBuffer buf = fc.map(MapMode.READ_ONLY, headerOffset, fc.size() - headerOffset);
			buf.order(ByteOrder.LITTLE_ENDIAN);
			MpqFile f = new MpqFile(buf , b, discBlockSize, name);
			f.extractToFile(dest);
		} catch (IOException e) {
			throw new JMpqException(e);
		}
		
	}
	
	public boolean hasFile(String name){
		try {
			hashTable.getBlockIndexOfFile(name);
		} catch (IOException e) {
			return false;
		}
		return true;
	}
	
	/**
	 * Extracts the specified file out of the mpq
	 * 
	 * @param name
	 *            of the file
	 * @param dest
	 *            the outputstream where the file's content is written
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public void extractFile(String name, OutputStream dest) throws JMpqException {
		try {
			int pos = hashTable.getBlockIndexOfFile(name);
			Block b = blockTable.getBlockAtPos(pos);
			MappedByteBuffer buf = fc.map(MapMode.READ_ONLY, headerOffset, fc.size() - headerOffset);
			buf.order(ByteOrder.LITTLE_ENDIAN);
			MpqFile f = new MpqFile(buf , b, discBlockSize, name);
			f.extractToOutputStream(dest);
		} catch (IOException e) {
			throw new JMpqException(e);
		}	
	}
	
	/**
	 * Deletes the specified file out of the mpq once you rebuild the mpq
	 * 
	 * @param name
	 *            of the file
	 * @param dest
	 *            to that the files content get copyed
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public void deleteFile(String name) throws JMpqException {
		listFile.removeFile(name);
	}

	/**
	 * Inserts the specified file out of the mpq once you rebuild the mpq
	 * 
	 * @param name
	 *            of the file
	 * @param dest
	 *            to that the files content get copyed
	 * @throws JMpqException
	 *             if file is not found or access errors occur
	 */
	public void insertFile(String name, File f) throws JMpqException {
		try {
			listFile.addFile(name);
			FileInputStream in = new FileInputStream(f);
			File temp = File.createTempFile("wurst", "crig");
			FileOutputStream out = new FileOutputStream(temp);
			int i = in.read();
			while(i != -1){
				out.write(i);
				i = in.read();
			}
			in.close();
			out.flush();
			out.close();
			filesToAdd.add(temp);
			internalFilename.put(temp, name);
		} catch (IOException e) {
			throw new JMpqException(e);
		}
		
	}
	
	@Override
	public String toString() {
		return "JMpqEditor [headerSize=" + headerSize + ", archiveSize=" + archiveSize + ", formatVersion="
				+ formatVersion + ", discBlockSize=" + discBlockSize + ", hashPos=" + hashPos + ", blockPos="
				+ blockPos + ", hashSize=" + hashSize + ", blockSize=" + blockSize + ", hashMap=" + hashTable + "]";
	}
}
