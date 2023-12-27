import java.io.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;

// compile: javac vmsim.java
// cmd args: vmsim –n <numframes> -a <opt|clock|lru> <tracefile>

public class vmsim {
    // parameters and variables
    private static int numFrames;
    private static String algo;
    private static String traceFile;
    private static int vAddress;
    private static String instructionBit;
    private static HashMap<Integer, Queue<Integer>> hashmap;

    // stats
    private static int memAccesses = 0;
    private static int pageFaults = 0;
    private static int writesToDisk = 0;

    public static void main(String args[]) {

        // validate input parameters
        if (args.length != 5) {
            System.out.println("Invalid number of parameters, enter 5");
            System.exit(0);
        }

        if (!(args[0].equals("-n") || args[2].equals("-a"))) {
            System.out.println("Invalid parameter");
            System.exit(0);
        }

        if (!(args[3].equals("opt") || args[3].equals("clock") || args[3].equals("lru"))) {
            System.out.println("Invalid algorithm");
            System.exit(0);
        }

        // parse arguments and assign parameter values
        parseArgs(args);

        // initialize two level page table creating a root table pointing to leaf tables
        TwoLevelPageTable pt = new TwoLevelPageTable();
        // initialize RAM with number of frames given
        RAM ram = new RAM(numFrames);

        // call whichever algo we're running
        if (algo.equals("lru")) {
            LRU(ram, pt);
        } else if (algo.equals("opt")) {
            OPT(ram, pt);
        } else {
            CLOCK(ram, pt);
        }

        // at end, print stats of simulation
        printStats();
    }

    // --------------------------------------- ALGORITHMS -----------------------------------------------

    private static void LRU(RAM ram, TwoLevelPageTable pt) {
        // loop through trace file
        try (BufferedReader trace = new BufferedReader(new FileReader(traceFile))) {
            String line;
            while ((line = trace.readLine()) != null) {
                // only look at trace lines, do nothing otherwise
                if (!line.startsWith("==")) {

                    // calculate page num
                    int pageNum = getPageNum(line);

                    // extracting root index and leaf index using bitwise operations
                    int rootIndex = vAddress >>> (23);
                    int leafIndex = (vAddress >>> 13) & 0x3FF;

                    // grab PTE
                    PageTableEntry pte = pt.getPTE(rootIndex, leafIndex);

                    // if valid bit is false, then we have a page fault
                    if (!pte.v) {
                        pageFaults++;

                        // assign pageNum to frame and update frame info
                        Frame frame = ram.allocateFrame(pte, pageNum);

                        // frames all full, do page replacement algo
                        if (frame == null) {
                            // grab lru frame
                            Frame lruFrame = ram.frames.getFirst();

                            // set valid bit to false as its about to be removed from RAM
                            lruFrame.pte.v = false;

                            // if the dirty bit of the associated pte is set, write to disk
                            if (lruFrame.pte.d) {
                                writesToDisk++;
                            }

                            // mark the frame as free
                            lruFrame.free = true;

                            // allocate frame now that space is available
                            ram.allocateFrame(pte, pageNum);
                        }
                        // page hit, need to update the positions of list
                    } else {
                        // get the frame associated with that pte
                        Frame pageHitFrame = ram.getFrame(pageNum);
                        // temporarily remove from list
                        ram.frames.remove(pageHitFrame);
                        // append it to the tail of the list since it is now MRU
                        ram.frames.add(pageHitFrame);
                    }
                    // at this point the frame has been loaded into RAM
                    // update valid and referenced bits
                    pte.v = true;
                    pte.r = true;
                    // increment mem accesses accordingly
                    calculateMemAccesses(pte);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void OPT(RAM ram, TwoLevelPageTable pt) {
        // loop through trace file first to see future accesses
        processTrace();
        // loop through trace file to complete simulation
        try (BufferedReader trace = new BufferedReader(new FileReader(traceFile))) {
            String line;
            int i = 0;
            while ((line = trace.readLine()) != null) {
                // only look at trace lines, do nothing otherwise
                if (!line.startsWith("==")) {

                    // calculate page num
                    int pageNum = getPageNum(line);

                    // extracting root index and leaf index using bitwise operations
                    int rootIndex = vAddress >>> (23);
                    int leafIndex = (vAddress >>> 13) & 0x3FF;

                    // grab PTE
                    PageTableEntry pte = pt.getPTE(rootIndex, leafIndex);

                    // if valid bit is false, then we have a page fault
                    if (!pte.v) {
                        pageFaults++;

                        // assign pageNum to frame and update frame info
                        Frame frame = ram.allocateFrame(pte, pageNum);

                        // frames all full, do page replacement algo
                        if (frame == null) {
                            int farthestAccess = -1;
                            int pageNumToEvict = -1;

                            // loop over each frame in ram
                            for (Frame f : ram.frames) {
                                // for each frame look at its page num's future accesses
                                Queue<Integer> accesses = hashmap.get(f.pageNum);
                                // if page is never accessed again, it's best candidate for eviction
                                if (accesses == null || accesses.isEmpty()) {
                                    pageNumToEvict = f.pageNum;
                                    break;
                                } else {
                                    // else grab the head of the list at this page num
                                    int access = accesses.peek();
                                    // if this access is > than farthestAccess
                                    if (access > farthestAccess) {
                                        // assign farthestAccess to value of this access
                                        farthestAccess = access;
                                        // mark this page as the one to evict
                                        pageNumToEvict = f.pageNum;
                                    }
                                }
                            }
                            // grab frame to evict
                            Frame frameToEvict = ram.getFrame(pageNumToEvict);
                            // set valid bit to false as its about to be removed from RAM
                            frameToEvict.pte.v = false;
                            // if the dirty bit of the associated pte is set, write to disk
                            if (frameToEvict.pte.d) {
                                writesToDisk++;
                            }

                            // mark frame as free
                            frameToEvict.free = true;
                            // put new frame into memory
                            ram.allocateFrame(pte, pageNum);
                            // remove corresponding access from queue
                            removeAccessed(pageNum, i);
                        }

                        // if frame wasn't null, remove corresponding access from queue
                        removeAccessed(pageNum, i);
                    } else {
                        // page hit, remove corresponding access from queue
                        removeAccessed(pageNum, i);
                    }
                    // at this point the frame has been loaded into RAM
                    // update valid and referenced bits
                    pte.v = true;
                    pte.r = true;

                    // increment mem accesses accordingly
                    calculateMemAccesses(pte);

                    // increment counter
                    i++;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void CLOCK(RAM ram, TwoLevelPageTable pt) {
        // loop through trace file
        try (BufferedReader trace = new BufferedReader(new FileReader(traceFile))) {
            String line;
            while ((line = trace.readLine()) != null) {
                // only look at trace lines, do nothing otherwise
                if (!line.startsWith("==")) {

                    // calculate page num
                    int pageNum = getPageNum(line);

                    // extracting root index and leaf index using bitwise operations
                    int rootIndex = vAddress >>> (23);
                    int leafIndex = (vAddress >>> 13) & 0x3FF;

                    // grab PTE
                    PageTableEntry pte = pt.getPTE(rootIndex, leafIndex);

                    // if valid bit is false, then we have a page fault
                    if (!pte.v) {
                        pageFaults++;

                        // assign pageNum to frame and update frame info
                        Frame frame = ram.allocateFrame(pte, pageNum);

                        // frames all full, do page replacement algo
                        if (frame == null) {
                            // grab current frame that clock is pointing to
                            Frame curFrame = ram.frames.get(ram.clockPtr);

                            while (true) {
                                // if cur frame is referenced
                                if (curFrame.pte.r) {
                                    // reset referenced bit and move clock ptr forward in list
                                    curFrame.pte.r = false;
                                    ram.clockPtr = (ram.clockPtr + 1) % ram.frames.size();
                                } else {
                                    // referenced bit is not set, found frame to evict
                                    // move clock ptr forward in list and break loop
                                    ram.clockPtr = (ram.clockPtr + 1) % ram.frames.size();
                                    break;
                                }
                            }

                            // set valid bit to false as its about to be removed from RAM
                            curFrame.pte.v = false;

                            // if the dirty bit of the associated pte is set, write to disk
                            if (curFrame.pte.d) {
                                writesToDisk++;
                            }

                            // mark the frame as free
                            curFrame.free = true;

                            // allocate frame now that space is available
                            ram.allocateFrame(pte, pageNum);
                        }
                    }

                    // at this point the frame has been loaded into RAM
                    // update valid and referenced bits
                    pte.v = true;
                    pte.r = true;
                    // increment mem accesses accordingly
                    calculateMemAccesses(pte);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --------------------------------------- HELPER METHODS -----------------------------------------------

    // pre-process trace for OPT
    private static void processTrace() {
        // create hashmap
        hashmap = new HashMap<Integer, Queue<Integer>>();
        // loop through trace file
        try (BufferedReader trace = new BufferedReader(new FileReader(traceFile))) {
            String line;
            int i = 0;
            while ((line = trace.readLine()) != null) {
                // only look at trace lines, do nothing otherwise
                if (!line.startsWith("==")) {

                    // calculate page num
                    int pageNum = getPageNum(line);

                    // add pageNum to hashmap with corresponding future accesses
                    hashmap.putIfAbsent(pageNum, new LinkedList<>());
                    hashmap.get(pageNum).add(i);

                    // increment count
                    i++;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // remove access from hashmap - for OPT
    private static void removeAccessed(int pageNum, int index) {
        // get queue of accesses for page num
        Queue<Integer> accesses = hashmap.get(pageNum);
        if (accesses != null) {
            // remove the index from the queue that's been accessed
            if (!accesses.isEmpty() && accesses.peek() == index) {
                accesses.poll();
            }
        }
    }

    // calculate number of memory accesses made
    private static void calculateMemAccesses(PageTableEntry pte) {
        // if instruction is a fetch or load increment mem accesses by 1
        if (instructionBit.equals("I") || instructionBit.equals("L")) {
            memAccesses++;
            // if a store, increment mem accesses by 1 and set dirty bit to true
        } else if (instructionBit.equals("S")) {
            memAccesses++;
            pte.d = true;
            // else we have a modify (read and write), increment mem accesses by 2 and set
            // dirty bit to true
        } else {
            memAccesses += 2;
            pte.d = true;
        }
    }

    // parse input arguments and set variables
    private static void parseArgs(String[] args) {
        // parse args and set parameter variables
        try {
            numFrames = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.out.println("Invalid argument");
            System.exit(0);
        }
        algo = args[3];
        traceFile = args[4];
    }

    // print out stats related to algo and page table
    private static void printStats() {
        System.out.printf("Algorithm: %s\n", algo);
        System.out.printf("Number of frames: %d\n", numFrames);
        System.out.printf("Total memory accesses: %d\n", memAccesses);
        System.out.printf("Total page faults: %d\n", pageFaults);
        System.out.printf("Total writes to disk: %d\n", writesToDisk);
        System.out.printf("Number of page table leaves: %d\n", 1024);
        System.out.printf("Total size of page table: %d bytes\n", calculateSize());
    }

    // calculate size of page table
    private static int calculateSize() {
        // assuming 4 byte pte
        int pteSize = 4;
        // 1024 entries holding 4 byte pte's
        int leafSize = pteSize * 1024;
        // 512 entries with 4 byte ptrs
        int rootSize = pteSize * 512;
        // size of root + size of all leaf tables
        return rootSize + (512 * leafSize);
    }

    // calculate page number
    private static int getPageNum(String line) {
        // remove spaces in trace string
        line = line.replaceAll("\\s+", "");
        // get instruction type
        instructionBit = line.substring(0, 1);
        // 8 hex digits = 32 bit VA
        String hexAddress = line.substring(1, 9);
        // convert hex string to 32 bit int
        vAddress = Integer.parseUnsignedInt(hexAddress, 16);
        // isolate page number bits, 19 bits for root (9) + leaf (10)
        return (vAddress >>> 13) & 0x7FFFF;
    }

    // --------------------------------------- CLASSES -----------------------------------------------

    // PTE
    public static class PageTableEntry {
        boolean v;
        boolean d;
        boolean r;

        public PageTableEntry(boolean v, boolean d, boolean r) {
            this.v = v;
            this.d = d;
            this.r = r;
        }
    }

    // leaf page table
    public static class LeafPageTable {
        PageTableEntry[] ptEntries;

        // initialize PTE's in each leaf page table
        public LeafPageTable() {
            // leaf = 10 bits, 2^10 = 1024 PTE's
            ptEntries = new PageTableEntry[1024];
            for (int i = 0; i < 1024; i++) {
                ptEntries[i] = new PageTableEntry(false, false, false);
            }
        }
    }

    // root page table
    public static class RootPageTable {
        LeafPageTable[] leaves;

        // create a root page table of given size, given 2^9 = 512 leaves
        public RootPageTable() {
            leaves = new LeafPageTable[512];
        }
    }

    // two level page table
    public static class TwoLevelPageTable {
        RootPageTable rootTable;

        public TwoLevelPageTable() {
            // initialize the root page table
            this.rootTable = new RootPageTable();
        }

        // obtain PTE
        public PageTableEntry getPTE(int rootIndex, int leafIndex) {
            // obtain leaf page table at root index
            LeafPageTable leafPt = rootTable.leaves[rootIndex];
            // if no leaf exists, create one and assign the root index to contain that leaf
            if (leafPt == null) {
                leafPt = new LeafPageTable();
                rootTable.leaves[rootIndex] = leafPt;
            }
            // return the PTE corresponding to the leaf index
            return leafPt.ptEntries[leafIndex];
        }
    }

    // RAM
    public static class RAM {
        private LinkedList<Frame> frames;
        // private int numFrames;
        private int clockPtr = 0;

        public RAM(int numFrames) {
            // this.numFrames = numFrames;
            this.frames = new LinkedList<>();

            // initialize frames in RAM
            for (int i = 0; i < numFrames; i++) {
                // initialize all page nums to -1, as real page nums cant be negative
                frames.add(new Frame(-1));
            }
        }

        // grab frame at page number
        public Frame getFrame(int pageNum) {
            for (Frame frame : frames) {
                if (frame.pageNum == pageNum) {
                    return frame;
                }
            }
            return null;
        }

        // allocate frame with page num and update frame info
        public Frame allocateFrame(PageTableEntry pte, int pageNum) {
            for (Frame frame : frames) {
                if (frame.free) {
                    // add new frame info
                    frame.pageNum = pageNum;
                    frame.pte = pte;
                    // mark as not free
                    frame.free = false;

                    // only if lru algo, temporarily remove from list
                    if (algo.equals("lru")) {
                        frames.remove(frame);
                        // append it to the tail of the list since it is now MRU
                        frames.add(frame);
                    }

                    return frame;
                }
            }
            return null;
        }
    }

    // frame
    public static class Frame {
        int pageNum;
        PageTableEntry pte;
        boolean free;

        public Frame(int pageNum) {
            this.pageNum = pageNum;
            this.pte = null;
            this.free = true;
        }
    }
}