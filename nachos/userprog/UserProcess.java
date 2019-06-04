package nachos.userprog;

import nachos.machine.*;

import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

import java.io.EOFException;
import java.util.*;
import java.lang.*;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 * 
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 * 
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
	/** Pipe buffer */
	public class Pipe extends OpenFile {

		public Pipe(String name) {
			super(null, "SynchConsole");
			this.pipeBuffer = new byte[pageSize];
			this.writePoint = 0;
			this.readPoint = 0;
			this.freeSize = pageSize;
			this.pipeLock = new Lock();
			this.pipeCond = new Condition(this.pipeLock);
			this.name = name;
			UserProcess.pipeMap.put(name, this);
		}

		public String getName(){
			return this.name;
		}

		public byte[] pipeBuffer;
		public int writePoint;
		public int readPoint;
		public int freeSize;
		private Lock pipeLock;
		private Condition pipeCond;
		private String name;

		public boolean writeHelper(int bufferAddr, int count){
			int cur_write = readVirtualMemory(bufferAddr, this.pipeBuffer, writePoint, count);
			if (cur_write != count) return false;
			freeSize -= cur_write;
			writePoint = (writePoint+cur_write)%pageSize;
			return true;
		}

		public int writePipe(int bufferAddr, int count) {
			this.pipeLock.acquire();
			/** @param vaddr the first byte of virtual memory to read.
	 			@param data the array where the data will be stored.
			 	@param offset the first byte to write in the array.
	 			@param length the number of bytes to transfer from virtual memory to the */
			//there is enough in the pipe buffer
			int result = count;
			while(count > 0){
				if(freeSize >= count) {
					if (pageSize - writePoint > count) {
						if (!writeHelper(bufferAddr, count)){this.pipeLock.release(); return -1; }
						else count = 0;
					}else{
						int first_write = pageSize - writePoint;
						if (!writeHelper(bufferAddr, count)){this.pipeLock.release(); return -1;}
						else{
							bufferAddr += first_write;
							count -= first_write;
						}
						if (!writeHelper(bufferAddr, count)){this.pipeLock.release(); return -1;}
						else count = 0;
					}
				}else{
					if(readPoint <= writePoint){
						int first_write = pageSize - writePoint;
						if (!writeHelper(bufferAddr, count)){this.pipeLock.release(); return -1;}
						else{
							bufferAddr += first_write;
							count -= first_write;
						}
						if (!writeHelper(bufferAddr, readPoint)){this.pipeLock.release(); return -1;}
						else count -= readPoint;
					}else{
						if (!writeHelper(bufferAddr, readPoint)){this.pipeLock.release(); return -1;}
						else count -= readPoint;
					}
					pipeCond.sleep();
				}
			}
			this.pipeLock.release();
			System.out.println("pipeBuffer write: \n"+ Lib.bytesToString(this.pipeBuffer,0,256));
			return result;
		}

		public boolean readHelper(int bufferAddr, int count){

			int cur_read= writeVirtualMemory(bufferAddr, this.pipeBuffer, readPoint, count);
			if (cur_read != count) return false;
			freeSize += cur_read;
			readPoint = (readPoint+cur_read)%pageSize;
			return true;
		}
		public int readPipe(int bufferAddr, int count){
				/**@param vaddr the first byte of virtual memory to write.
				 * @param data the array containing the data to transfer.
				 * @param offset the first byte to transfer from the array.
				 * @param length the number of bytes to transfer from the array to virtual
				 * memory. */
			this.pipeLock.acquire();
			//there is enough in the pipe buffer
			int result = count;
			count = Math.min(pageSize - freeSize, count);
			if (readPoint + count < pageSize) {
				if (!readHelper(bufferAddr, count)){ this.pipeLock.release(); return -1;}
				System.out.println("pipeBuffer read: \n"+readVirtualMemoryString(bufferAddr,256));
//				String read = readVirtualMemoryString(bufferAddr,256);
//				String write = Lib.bytesToString(this.pipeBuffer,0,256);
//				for(int i = 0; i<256; i++){
//					System.out.println(read.charAt(i) == write.charAt(i));
//				}
			}else{
				int first_read = pageSize - readPoint;
				if (!readHelper(bufferAddr, first_read)){ this.pipeLock.release(); return -1;}
				else{
					bufferAddr += first_read;
					count -= first_read;
				}
				if (!readHelper(bufferAddr, count)){ this.pipeLock.release(); return -1;}
			}
			this.pipeLock.release();
			return result;
		}
	}
		/**
		readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
			Lib.assertTrue(offset >= 0 && length >= 0
					&& offset + length <= data.length);

			byte[] memory = Machine.processor().getMemory();

			if (vaddr < 0 || vaddr >= memory.length) return 0;

			int vpn = vaddr/pageSize;
			int off = vaddr%pageSize;
			int first = offset;
			// TODO: should check whether it's valid bit???
			int paddr = 0;
			int read = 0;
			length = Math.min(length, numPages*pageSize - vaddr);
			while(length > 0){
				if (!pageTable[vpn].valid) break;
				paddr = pageTable[vpn].ppn * pageSize + off;
				read = Math.min(length, pageSize - off);
				System.arraycopy(memory, paddr, data, offset, read);
				off = 0;
				offset += read;
				length -= read;
				vpn++;
			}
			return offset - first;
		 */

	/**
	 * Allocate a new process.
	 */
	public UserProcess() {

		UserKernel.processCountLock.acquire();
		this.pid = UserKernel.nextPID;
		UserKernel.nextPID++;
		UserKernel.processCount++;
		UserKernel.processCountLock.release();
		System.out.println("PID is: "+this.pid);

//		this.joinLock = new Lock();
//		this.joinCondition = new Condition(joinLock);

//		todo: page table entries
//		int numPhysPages = Machine.processor().getNumPhysPages();
//		pageTable = new TranslationEntry[numPhysPages];
//		for (int i = 0; i < numPhysPages; i++)
//			pageTable[i] = new TranslationEntry(-1, -1, false, true, false, false);

		fileDescTable = new OpenFile[s_fileTableSize];
		fileDescTable[0] = UserKernel.console.openForReading();
		fileDescTable[1] = UserKernel.console.openForWriting();
	}

	/**
	 * Allocate and return a new process of the correct class. The class name is
	 * specified by the <tt>nachos.conf</tt> key
	 * <tt>Kernel.processClassName</tt>.
	 * 
	 * @return a new process of the correct class.
	 */
	public static UserProcess newUserProcess() {
		String name = Machine.getProcessClassName ();

		// If Lib.constructObject is used, it quickly runs out
		// of file descriptors and throws an exception in
		// createClassLoader.  Hack around it by hard-coding
		// creating new processes of the appropriate type.

		if (name.equals ("nachos.userprog.UserProcess")) {
		    return new UserProcess ();
		} else if (name.equals ("nachos.vm.VMProcess")) {
		    return new VMProcess ();
		} else {
		    return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
		}
	}

	/**
	 * Execute the specified program with the specified arguments. Attempts to
	 * load the program, and then forks a thread to run it.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the program was successfully executed.
	 */
	public boolean execute(String name, String[] args) {
		if (!load(name, args))
			return false;

		thread = new UThread(this);
		thread.setName(name).fork();

		return true;
	}

	/**
	 * Save the state of this process in preparation for a context switch.
	 * Called by <tt>UThread.saveState()</tt>.
	 */
	public void saveState() {
	}

	/**
	 * Restore the state of this process after a context switch. Called by
	 * <tt>UThread.restoreState()</tt>.
	 */
	public void restoreState() {
		Machine.processor().setPageTable(pageTable);
	}

	/**
	 * Read a null-terminated string from this process's virtual memory. Read at
	 * most <tt>maxLength + 1</tt> bytes from the specified address, search for
	 * the null terminator, and convert it to a <tt>java.lang.String</tt>,
	 * without including the null terminator. If no null terminator is found,
	 * returns <tt>null</tt>.
	 * 
	 * @param vaddr the starting virtual address of the null-terminated string.
	 * @param maxLength the maximum number of characters in the string, not
	 * including the null terminator.
	 * @return the string read, or <tt>null</tt> if no null terminator was
	 * found.
	 */
	public String readVirtualMemoryString(int vaddr, int maxLength) {
		Lib.assertTrue(maxLength >= 0);

		byte[] bytes = new byte[maxLength + 1];

		int bytesRead = readVirtualMemory(vaddr, bytes);

		for (int length = 0; length < bytesRead; length++) {
			if (bytes[length] == 0)
				return new String(bytes, 0, length);
		}

		return null;
	}

	/**
	 * Transfer data from this process's virtual memory to all of the specified
	 * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 *
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data) {
		return readVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from this process's virtual memory to the specified array.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to read.
	 * @param data the array where the data will be stored.
	 * @param offset the first byte to write in the array.
	 * @param length the number of bytes to transfer from virtual memory to the
	 * array.
	 * @return the number of bytes successfully transferred.
	 */
	public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();

		if (vaddr < 0 || vaddr >= memory.length) return 0;
//		int vpn = vaddr/pageSize;
//		int off = vaddr%pageSize;
		int vpn = Processor.pageFromAddress(vaddr);
		int off = Processor.offsetFromAddress(vaddr);
		int first = offset;
		// TODO: should check whether it's valid bit???
		int paddr = 0;
		int read = 0;
		length = Math.min(length, numPages*pageSize - vaddr);
		while(length > 0){
			if (!pageTable[vpn].valid) break;
			paddr = pageTable[vpn].ppn * pageSize + off;
			read = Math.min(length, pageSize - off);
			System.arraycopy(memory, paddr, data, offset, read);
			off = 0;
			offset += read;
			length -= read;
			vpn++;
		}
		return offset - first;
//		Lib.assertTrue(offset >= 0 && length >= 0
//				&& offset + length <= data.length);
//
//		byte[] memory = Machine.processor().getMemory();
//
//		// for now, just assume that virtual addresses equal physical addresses
//		if (vaddr < 0 || vaddr >= memory.length)
//			return 0;
//
//		int amount = Math.min(length, memory.length - vaddr);
//		System.arraycopy(memory, vaddr, data, offset, amount);
//
//		return amount;
	}

	/**
	 * Transfer all data from the specified array to this process's virtual
	 * memory. Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data) {
		return writeVirtualMemory(vaddr, data, 0, data.length);
	}

	/**
	 * Transfer data from the specified array to this process's virtual memory.
	 * This method handles address translation details. This method must
	 * <i>not</i> destroy the current process if an error occurs, but instead
	 * should return the number of bytes successfully copied (or zero if no data
	 * could be copied).
	 * 
	 * @param vaddr the first byte of virtual memory to write.
	 * @param data the array containing the data to transfer.
	 * @param offset the first byte to transfer from the array.
	 * @param length the number of bytes to transfer from the array to virtual
	 * memory.
	 * @return the number of bytes successfully transferred.
	 */
	public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
		Lib.assertTrue(offset >= 0 && length >= 0
				&& offset + length <= data.length);

		byte[] memory = Machine.processor().getMemory();
		if (vaddr < 0 || vaddr >= memory.length)
			return 0;
		int vpn = Processor.pageFromAddress(vaddr);
		int off = Processor.offsetFromAddress(vaddr);
//		int vpn = vaddr/pageSize;
//		int off = vaddr%pageSize;
		int first = offset;
		int paddr = 0;
		int write = 0;
		int amount = Math.min(length, numPages * pageSize - vaddr);
		while(amount > 0){
			if(!pageTable[vpn].valid) break;
			//if vaddr in read only section, return -1
			if(pageTable[vpn].readOnly) return -1;
			paddr = pageTable[vpn].ppn * pageSize + off;
			write = Math.min(amount, pageSize-off);
			System.arraycopy(data, offset, memory, paddr, amount);
			off = 0;
			vpn++;
			offset += write;
			amount -= write;
		}
		return offset-first;
//		Lib.assertTrue(offset >= 0 && length >= 0
//				&& offset + length <= data.length);
//
//		byte[] memory = Machine.processor().getMemory();
//
//		// for now, just assume that virtual addresses equal physical addresses
//		if (vaddr < 0 || vaddr >= memory.length)
//			return 0;
//
//		int amount = Math.min(length, memory.length - vaddr);
//		System.arraycopy(data, offset, memory, vaddr, amount);
//
//		return amount;
	}

	/**
	 * Load the executable with the specified name into this process, and
	 * prepare to pass it the specified arguments. Opens the executable, reads
	 * its header information, and copies sections and arguments into this
	 * process's virtual memory.
	 * 
	 * @param name the name of the file containing the executable.
	 * @param args the arguments to pass to the executable.
	 * @return <tt>true</tt> if the executable was successfully loaded.
	 */
	private boolean load(String name, String[] args) {
		Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

		OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
		if (executable == null) {
			Lib.debug(dbgProcess, "\topen failed");
			return false;
		}

		try {
			coff = new Coff(executable);
		}
		catch (EOFException e) {
			executable.close();
			Lib.debug(dbgProcess, "\tcoff load failed");
			return false;
		}

		// make sure the sections are contiguous and start at page 0
		numPages = 0;
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);
			if (section.getFirstVPN() != numPages) {
				coff.close();
				Lib.debug(dbgProcess, "\tfragmented executable");
				return false;
			}
			numPages += section.getLength();
		}

		// make sure the argv array will fit in one page
		byte[][] argv = new byte[args.length][];
		int argsSize = 0;
		for (int i = 0; i < args.length; i++) {
			argv[i] = args[i].getBytes();
			// 4 bytes for argv[] pointer; then string plus one for null byte
			argsSize += 4 + argv[i].length + 1;
		}
		if (argsSize > pageSize) {
			coff.close();
			Lib.debug(dbgProcess, "\targuments too long");
			return false;
		}

		// program counter initially points at the program entry point
		initialPC = coff.getEntryPoint();

		// next comes the stack; stack pointer initially points to top of it
		numPages += stackPages;
		initialSP = numPages * pageSize;

		// and finally reserve 1 page for arguments
		numPages++;

		if (!loadSections())
			return false;

		// store arguments in last page
		int entryOffset = (numPages - 1) * pageSize;
		int stringOffset = entryOffset + args.length * 4;

		this.argc = args.length;
		this.argv = entryOffset;

		for (int i = 0; i < argv.length; i++) {
			byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
//			System.out.println(entryOffset);
//			System.out.println(stringOffsetBytes);
//			System.out.println(writeVirtualMemory(entryOffset, stringOffsetBytes));
			Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
			entryOffset += 4;
			Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
			stringOffset += argv[i].length;
			Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
			stringOffset += 1;
		}

		return true;
	}

	/**
	 * Allocates memory for this process, and loads the COFF sections into
	 * memory. If this returns successfully, the process will definitely be run
	 * (this is the last step in process initialization that can fail).
	 * 
	 * @return <tt>true</tt> if the sections were successfully loaded.
	 */
	protected boolean loadSections() {

		UserKernel.physicalPageSema.P();
		this.pageTable = new TranslationEntry[numPages];
		if (numPages > UserKernel.physicalPageList.size()) {
			//If a user program requests pages from the kernel, and cannot acquire them, we should abort,
			// close the coff file, and return false from load section.
			coff.close();
			Lib.debug(dbgProcess, "\tinsufficient physical memory");
			UserKernel.physicalPageSema.V();
			return false;
		}else {
			for(int i = 0; i<numPages; i++) {
				int ppn = UserKernel.physicalPageList.remove(0);
				this.pageTable[i] = new TranslationEntry(i, ppn, true, false, false, false);
			}
		}
		UserKernel.physicalPageSema.V();

		// load sections
		for (int s = 0; s < coff.getNumSections(); s++) {
			CoffSection section = coff.getSection(s);

			Lib.debug(dbgProcess, "\tinitializing " + section.getName()
					+ " section (" + section.getLength() + " pages)");

			for (int i = 0; i < section.getLength(); i++) {
				int vpn = section.getFirstVPN() + i;
				//determine which sections are ready only using coff.isReadOnly()
				this.pageTable[vpn].readOnly = section.isReadOnly();
				section.loadPage(i, this.pageTable[vpn].ppn);
//				// for now, just assume virtual addresses=physical addresses
//				section.loadPage(i, vpn);
			}
		}
		return true;
	}

	/**
	 * Release any resources allocated by <tt>loadSections()</tt>.
	 */
	protected void unloadSections() {
		UserKernel.physicalPageSema.P();
		for(int i = 0; i<this.pageTable.length; i++) {
			if(this.pageTable[i] != null && this.pageTable[i].valid) {
				this.pageTable[i].valid = false;
				UserKernel.physicalPageList.add(pageTable[i].ppn);
			}
		}
		UserKernel.physicalPageSema.V();
	}

	/**
	 * Initialize the processor's registers in preparation for running the
	 * program loaded into this process. Set the PC register to point at the
	 * start function, set the stack pointer register to point at the top of the
	 * stack, set the A0 and A1 registers to argc and argv, respectively, and
	 * initialize all other registers to 0.
	 */
	public void initRegisters() {
		Processor processor = Machine.processor();

		// by default, everything's 0
		for (int i = 0; i < processor.numUserRegisters; i++)
			processor.writeRegister(i, 0);

		// initialize PC and SP according
		processor.writeRegister(Processor.regPC, initialPC);
		processor.writeRegister(Processor.regSP, initialSP);

		// initialize the first two argument registers to argc and argv
		processor.writeRegister(Processor.regA0, argc);
		processor.writeRegister(Processor.regA1, argv);
	}

	/** FILE MANAGEMENT SYSCALLS: creat, open, read, write, close, unlink
	 *
	 * A file descriptor is a small, non-negative integer that refers to a file on
	 * disk or to a stream (such as console input, console output, and network
	 * connections). A file descriptor can be passed to read() and write() to
	 * read/write the corresponding file/stream. A file descriptor can also be
	 * passed to close() to release the file descriptor and any associated
	 * resources.
	 */

	/**
	 * Attempt to open the named disk file, creating it if it does not exist,
	 * and return a file descriptor that can be used to access the file.
	 *
	 * Note that creat() can only be used to create files on disk; creat() will
	 * never return a file descriptor referring to a stream.
	 *
	 * Returns the new file descriptor, or -1 if an error occurred.
	 */

	/**
	 * #mcip
	 * Handle the create() system call
	 */
	private int handleCreate(int vaddr){
		if(vaddr < 0) return -1;
		String file_name = readVirtualMemoryString(vaddr, 256);
		if(file_name == null) return -1;

		OpenFile o_file = null;
		Pipe p_file = null;
		if(file_name.toLowerCase().startsWith("/pipe/")) {
			if (UserProcess.pipeMap.containsKey(file_name)) return -1;
			p_file = new Pipe(file_name);
		}else {
			o_file = ThreadedKernel.fileSystem.open(new String(file_name), true);
			if (o_file == null) return -1;
			//		System.out.println("c1");
		}
		int empty_ind = -1;
		for (int i = 2; i < s_fileTableSize; i++) {
			if (this.fileDescTable[i] == null) {
				empty_ind = i;
				break;
			}
		}
		if (empty_ind == -1) return -1;

		this.fileDescTable[empty_ind] = o_file != null? o_file : p_file;
		return empty_ind;
	}

	/**
	 * Attempt to open the named file and return a file descriptor.
	 *
	 * Note that open() can only be used to open files on disk; open() will never
	 * return a file descriptor referring to a stream.
	 *
	 * Returns the new file descriptor, or -1 if an error occurred.
	 */
	private int handleOpen(int vaddr){
		if(vaddr < 0) return -1;

		String file_name = readVirtualMemoryString(vaddr, 256);
		if(file_name == null) return -1;

		int empty_ind = -1;
		for (int i = 2; i < fileDescTable.length; i++){
			//opening the same file multiple times returns different file descriptors for each open
			if(this.fileDescTable[i] == null){
				empty_ind = i;
				break;
			}
		}
		if (empty_ind == -1) return -1;

		OpenFile o_file = null;
		Pipe p_file = null;
		if(file_name.toLowerCase().startsWith("/pipe/")) {
			p_file = UserProcess.pipeMap.getOrDefault(file_name, null);
			if(p_file == null) return -1;
		}else {
			o_file = ThreadedKernel.fileSystem.open(new String(file_name), false);
			if (o_file == null) return -1;
			//		System.out.println("c1");
		}
		this.fileDescTable[empty_ind] = o_file!=null?o_file:p_file;
		return empty_ind;
	}

	/**
	 * Attempt to read up to count bytes into buffer from the file or stream
	 * referred to by fileDescriptor.
	 *
	 * On success, the number of bytes read is returned. If the file descriptor
	 * refers to a file on disk, the file position is advanced by this number.
	 *
	 * It is not necessarily an error if this number is smaller than the number of
	 * bytes requested. If the file descriptor refers to a file on disk, this
	 * indicates that the end of the file has been reached. If the file descriptor
	 * refers to a stream, this indicates that the fewer bytes are actually
	 * available right now than were requested, but more bytes may become available
	 * in the future. Note that read() never waits for a stream to have more data;
	 * it always returns as much as possible immediately.
	 *
	 * On error, -1 is returned, and the new file position is undefined. This can
	 * happen if fileDescriptor is invalid, if part of the buffer is read-only or
	 * invalid, or if a network stream has been terminated by the remote host and
	 * no more data is available.
	 */
	private int handleRead(int fileDescriptor, int bufferAddr, int count){
		if(fileDescriptor < 0 || fileDescriptor>=s_fileTableSize || count < 0) return -1;
		OpenFile file = null;
		Pipe p_file = null;
		if (!(this.fileDescTable[fileDescriptor] instanceof Pipe)) file = this.fileDescTable[fileDescriptor];
		else p_file = (Pipe)this.fileDescTable[fileDescriptor];
		if(file == null && p_file == null) return -1;

		byte[] buffer = new byte[pageSize];
		int bytes_read = 0, cur_ker_read = 0, cur_pro_write = 0;

		while(count > 0){
			if(file !=null) cur_ker_read = file.read(buffer, 0, Math.min(count, pageSize));
			else cur_ker_read = p_file.readPipe(bufferAddr, count);
			if(cur_ker_read <= 0) return bytes_read;

			cur_pro_write = writeVirtualMemory(bufferAddr, buffer, 0, cur_ker_read);
			if(cur_ker_read != cur_pro_write) return -1;
			bufferAddr += cur_ker_read;
			bytes_read += cur_ker_read;
			count -= cur_ker_read;
		}
		return bytes_read;
	}

	/**
	 * Attempt to write up to count bytes from buffer to the file or stream
	 * referred to by fileDescriptor. write() can return before the bytes are
	 * actually flushed to the file or stream. A write to a stream can block,
	 * however, if kernel queues are temporarily full.
	 *
	 * On success, the number of bytes written is returned (zero indicates nothing
	 * was written), and the file position is advanced by this number. It IS an
	 * error if this number is smaller than the number of bytes requested. For
	 * disk files, this indicates that the disk is full. For streams, this
	 * indicates the stream was terminated by the remote host before all the data
	 * was transferred.
	 *
	 * On error, -1 is returned, and the new file position is undefined. This can
	 * happen if fileDescriptor is invalid, if part of the buffer is invalid, or
	 * if a network stream has already been terminated by the remote host.
	 */
	private int handleWrite(int fileDescriptor, int bufferAddr, int count){
		if(fileDescriptor < 0 || fileDescriptor>=s_fileTableSize || count < 0) {
			Lib.debug(dbgProcess, String.format("fD %d not in range or count < 0", fileDescriptor));
			return -1;
		}
		Pipe p_file = null;
		OpenFile file = null;
		if(!(this.fileDescTable[fileDescriptor] instanceof  Pipe)) file = this.fileDescTable[fileDescriptor];
		else p_file = (Pipe)this.fileDescTable[fileDescriptor];
		if(file == null && p_file == null) {
			Lib.debug(dbgProcess, "file DNE");
			return -1;
		}

		byte[] buffer = new byte[pageSize];
		int bytes_write = 0, cur_pro_read = 0, cur_ker_write = 0;

		// your method should return -1 to indicate an error, and on an error the new file position is undefined
		// so it should be okay to write all of the data to the file.
		while(count > 0){
			cur_pro_read = readVirtualMemory(bufferAddr, buffer, 0, Math.min(count, pageSize));
			if(cur_pro_read <= 0) {
				Lib.debug(dbgProcess, "doesnt read any");
				return -1;
			}

			if (file!=null) cur_ker_write = file.write(buffer, 0, cur_pro_read);
			else cur_ker_write = p_file.writePipe(bufferAddr, count);

//			cur_ker_write = file.write(bufferAddr, buffer, 0, cur_pro_read);
			if(cur_ker_write != cur_pro_read) {
				Lib.debug(dbgProcess, "write not equal to read");
				return -1;
			}

			bytes_write += cur_pro_read;
			bufferAddr += cur_pro_read;
			count -= cur_pro_read;
		}
		return bytes_write;
	}

	/**
	 * Close a file descriptor, so that it no longer refers to any file or
	 * stream and may be reused. The resources associated with the file
	 * descriptor are released.
	 *
	 * Returns 0 on success, or -1 if an error occurred.
	 */
	private int handleClose(int fileDescriptor){
		if(fileDescriptor < 0 || fileDescriptor>=s_fileTableSize) return -1;
		OpenFile file = this.fileDescTable[fileDescriptor];
		if(file == null) return -1;
		file.close();
		this.fileDescTable[fileDescriptor] = null;
		return 0;
	}

	/**
	 * Delete a file from the file system.
	 *
	 * If another process has the file open, the underlying file system
	 * implementation in StubFileSystem will cleanly handle this situation
	 * (this process will ask the file system to remove the file, but the
	 * file will not actually be deleted by the file system until all
	 * other processes are done with the file).
	 *
	 * Returns 0 on success, or -1 if an error occurred.
	 */
	private int handleUnlink(int vaddr){
		if(vaddr < 0) return -1;
		String file_name = readVirtualMemoryString(vaddr, 256);
		int to_close = -1;
		for(int i = 0; i<this.fileDescTable.length; i++){
			if(this.fileDescTable[i] != null && this.fileDescTable[i].getName().equals(file_name)){
				//since opening the same file multiple times returns different file descriptors for each open
				handleClose(i);
			}
		}
//		if(to_close != -1) handleClose(to_close);
		if(ThreadedKernel.fileSystem.remove(file_name)) return 0;
		return -1;
	}

	/* PROCESS MANAGEMENT SYSCALLS: exit(), exec(), join() */
	/**
	 * Terminate the current process immediately. Any open file descriptors
	 * belonging to the process are closed. Any children of the process no longer
	 * have a parent process.
	 *
	 * status is returned to the parent process as this process's exit status and
	 * can be collected using the join syscall. A process exiting normally should
	 * (but is not required to) set status to 0.
	 *
	 * exit() never returns.
	 */
	private int handleExit(int status) {  //void exit(int status);
	        // Do not remove this call to the autoGrader...
		Machine.autoGrader().finishingCurrentProcess(status);
		// ...and leave it as the top of handleExit so that we
		// can grade your implementation.

		//close all files in file table
		for(int i = 0;i<16; i++) this.handleClose(i);
		//delete all memory by unloadSections()
		this.unloadSections();
		Lib.debug(dbgProcess, "UserProcess.handleExit (" + status + ")");
		//close the coff
		coff.close();
		//if it has a parent process, save the status for parent, join() will need it
		if(this.parent != null) {
			this.parent.status = status;
//			this.joinCondition.wakeAll();
			//wake up parent if sleeping
//			this.parent.thread.ready();
		} /** if exits normally */

		UserKernel.processCountLock.acquire();
		UserKernel.processCount--;
		UserKernel.processCountLock.release();
		System.out.printf("[handle exit]: pid <%d>, process count <%d>\n", this.pid, UserKernel.processCount);
		UserKernel.childStatusMapLock.acquire();
		this.childStatusMap.put(this.pid, 0);
		UserKernel.childStatusMapLock.release();
		if (UserKernel.processCount == 0){
			System.out.println("[handle exit]: Now halt: "+ this.pid);
			//In case of last process, terminate the current process
			Kernel.kernel.terminate();
		}else{
			System.out.println("Exit a process ok: "+ this.pid);

			//Close Kthread by calling KThread.finish()
			this.thread.finish();

		}
		return 0;
//		this.unloadSections();
//		coff.close();
//		Lib.debug(dbgProcess, "UserProcess.handleExit (" + status + ")");
//		// for now, unconditionally terminate with just one process
//		Kernel.kernel.terminate();
//		return 0;
	}

	/**
	 * Execute the program stored in the specified file, with the specified
	 * arguments, in a new child process. The child process has a new unique
	 * process ID, and starts with stdin opened as file descriptor 0, and stdout
	 * opened as file descriptor 1.
	 *
	 * file is a null-terminated string that specifies the name of the file
	 * containing the executable. Note that this string must include the ".coff"
	 * extension.
	 *
	 * argc specifies the number of arguments to pass to the child process. This
	 * number must be non-negative.
	 *
	 * argv is an array of pointers to null-terminated strings that represent the
	 * arguments to pass to the child process. argv[0] points to the first
	 * argument, and argv[argc-1] points to the last argument.
	 *
	 * exec() returns the child process's process ID, which can be passed to
	 * join(). On error, returns -1.
	 */
	/** exec(char *name, int argc, char **argv); */
	private int handleExec(int coffName, int argc, int argv){
		//TODO: need to check argv >= 0?
		if(coffName < 0 || argc<0 || argv < 0) return -1;
		String file_name = readVirtualMemoryString(coffName, 256);
		//file name need to include .coff
		System.out.println(("File name: "+file_name +", argc: "+argc));
		if(!file_name.toLowerCase().endsWith(".coff")) return -1;

		//TODO: Chao hui Yu
		byte[] bytes = new byte[argc*4];
		int byteRead = this.readVirtualMemory(argv, bytes);
		if(byteRead != argc*4) return -1; // check if bytes read is ok
		String[] args = new String[argc];
		for (int i = 0; i<argc; i++){
			int vaddr_arg = Lib.bytesToInt(bytes, i*4);
			args[i] = this.readVirtualMemoryString(vaddr_arg, 256);
			if(args[i] == null) return -1;  // check if a String is read
			System.out.println("Argument: "+args[i]);
		}

		UserProcess child = new UserProcess();
		this.childMap.put(child.pid, child);
		child.parent = this;
		if (!child.execute(file_name, args)) {
//			System.out.println("parameter");
			return -1;
		}
		System.out.println("Child PID: "+child.pid);
		return child.pid;
	}

	/**
	 * Suspend execution of the current process until the child process specified
	 * by the processID argument has exited. If the child has already exited by the
	 * time of the call, returns immediately. When the current process resumes, it
	 * disowns the child process, so that join() cannot be used on that process
	 * again.
	 *
	 * processID is the process ID of the child process, returned by exec().
	 *
	 * status points to an integer where the exit status of the child process will
	 * be stored. This is the value the child passed to exit(). If the child exited
	 * because of an unhandled exception, the value stored is not defined.
	 *
	 * If the child exited normally, returns 1. If the child exited as a result of
	 * an unhandled exception, returns 0. If processID does not refer to a child
	 * process of the current process, returns -1.
	 */
	private int handleJoin(int processID, int status){ //int* status
		UserProcess child = this.childMap.get(processID);
		if(child == null) return -1; // PID doenst refer to a child process of the current process
		this.childMap.remove(processID);
		//Sleep on child
		child.thread.join();

//		joinLock.acquire();
//		joinCondition.sleep();
//		joinLock.release();

		// child exited as result of unhandled exception, return 0
		UserKernel.childStatusMapLock.acquire();
		int child_exit = this.childStatusMap.getOrDefault(processID, 10);
		UserKernel.childStatusMapLock.release();
		if (child_exit == -1) return 0;

		//in a number of cases, we are treating NULL=0x0 as a special case.
		if (status != 0) {
			//Get and set child status, which is a virtual address
			//Larger byte on the left
			byte[] buffer = {(byte) (child.status >> 24), (byte) (child.status >> 16),
					(byte) (child.status >> 8), (byte) (child.status)};
			if (writeVirtualMemory(status, buffer) < 4) return -1; // TODO: what about this?
		}
		return 1; // child exited normal return 1
	}

	/**
	 Halt the Nachos machine by calling Machine.halt(). Only the root process
	 * (the first process, executed by UserKernel.run()) should be allowed to
	 * execute this syscall. Any other process should ignore the syscall and return
	 * immediately.
	 */
	private int handleHalt() {
		if (this.pid == 0) {
			Machine.halt();
		}
		//another process invoked halt, should return -1
		Lib.assertNotReached("Machine.halt() did not halt machine!");
		return -1;
	}

	private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2,
			syscallJoin = 3, syscallCreate = 4, syscallOpen = 5,
			syscallRead = 6, syscallWrite = 7, syscallClose = 8,
			syscallUnlink = 9;

	/**
	 * Handle a syscall exception. Called by <tt>handleException()</tt>. The
	 * <i>syscall</i> argument identifies which syscall the user executed:
	 * 
	 * <table>
	 * <tr>
	 * <td>syscall#</td>
	 * <td>syscall prototype</td>
	 * </tr>
	 * <tr>
	 * <td>0</td>
	 * <td><tt>void halt();</tt></td>
	 * </tr>
	 * <tr>
	 * <td>1</td>
	 * <td><tt>void exit(int status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>2</td>
	 * <td><tt>int  exec(char *name, int argc, char **argv);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>3</td>
	 * <td><tt>int  join(int pid, int *status);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>4</td>
	 * <td><tt>int  creat(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>5</td>
	 * <td><tt>int  open(char *name);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>6</td>
	 * <td><tt>int  read(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>7</td>
	 * <td><tt>int  write(int fd, char *buffer, int size);
	 * 								</tt></td>
	 * </tr>
	 * <tr>
	 * <td>8</td>
	 * <td><tt>int  close(int fd);</tt></td>
	 * </tr>
	 * <tr>
	 * <td>9</td>
	 * <td><tt>int  unlink(char *name);</tt></td>
	 * </tr>
	 * </table>
	 * 
	 * @param syscall the syscall number.
	 * @param a0 the first syscall argument.
	 * @param a1 the second syscall argument.
	 * @param a2 the third syscall argument.
	 * @param a3 the fourth syscall argument.
	 * @return the value to be returned to the user.
	 */
	public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
		switch (syscall) {
		case syscallHalt:
			return handleHalt();
		case syscallExit:
			return handleExit(a0);
		case syscallCreate:
			return handleCreate(a0);
		case syscallOpen:
			return handleOpen(a0);
		case syscallRead:
			return handleRead(a0, a1, a2);
		case syscallWrite:
			return handleWrite(a0, a1, a2);
		case syscallClose:
			return handleClose(a0);
		case syscallUnlink:
			return handleUnlink(a0);
		case syscallExec:
			return handleExec(a0, a1, a2);
		case syscallJoin:
			return handleJoin(a0, a1);
		default:
			Lib.debug(dbgProcess, "Unknown syscall " + syscall);
			Lib.assertNotReached("Unknown system call!");
		}
		return 0;
	}

	/**
	 * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>
	 * . The <i>cause</i> argument identifies which exception occurred; see the
	 * <tt>Processor.exceptionZZZ</tt> constants.
	 * 
	 * @param cause the user exception that occurred.
	 */
	public void handleException(int cause) {
		Processor processor = Machine.processor();

		switch (cause) {
		case Processor.exceptionSyscall:
			int result = handleSyscall(processor.readRegister(Processor.regV0),
					processor.readRegister(Processor.regA0),
					processor.readRegister(Processor.regA1),
					processor.readRegister(Processor.regA2),
					processor.readRegister(Processor.regA3));
			processor.writeRegister(Processor.regV0, result);
			processor.advancePC();
			break;

		default:
			System.out.println("mcip: handle exception");
			if(this.parent != null) {
				UserKernel.childStatusMapLock.acquire();
				this.parent.childStatusMap.put(this.pid, -1);
				UserKernel.childStatusMapLock.release();
			}

			for(int i = 0;i<16; i++) this.handleClose(i);
			//delete all memory by unloadSections()
			this.unloadSections();
			//close the coff
			coff.close();

			UserKernel.processCountLock.acquire();
			UserKernel.processCount--;
			UserKernel.processCountLock.release();

			if (UserKernel.processCount == 0){
				System.out.println("mcip: Now halt");
				//In case of last process, terminate the current process
				Kernel.kernel.terminate();
			}else{
				System.out.println("mcip: Exit a process ok");
				//Close Kthread by calling KThread.finish()
				this.thread.finish();

			}

			Lib.debug(dbgProcess, "Unexpected exception: "
					+ Processor.exceptionNames[cause]);
			Lib.assertNotReached("Unexpected exception");
		}
	}
	/** Added by mcip */
	public static int s_fileTableSize = 16;
	protected OpenFile[] fileDescTable;
	private UserProcess parent;
	private Map<Integer, UserProcess> childMap = new HashMap<>();
	public Map<Integer, Integer> childStatusMap = new HashMap<>(); //-1 due to exception, 0 if exitted
	private int status;
	private int pid;
//	private Lock joinLock;
//	private Condition joinCondition;
//	private boolean exitWithException = false;
	private static Map<String, Pipe> pipeMap = new HashMap<>();

	/** The program being run by this process. */
	protected Coff coff;

	/** This process's page table. */
	protected TranslationEntry[] pageTable;

	/** The number of contiguous pages occupied by the program. */
	protected int numPages;

	/** The number of pages in the program's stack. */
	protected final int stackPages = 8;

	/** The thread that executes the user-level program. */
	protected UThread thread;
    
	private int initialPC, initialSP;

	private int argc, argv;

	private static final int pageSize = Processor.pageSize;

	private static final char dbgProcess = 'a';


}
