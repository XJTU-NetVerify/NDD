


package jdd.internal.profiler;


import jdd.util.*;

import java.util.*;

/**
 * profiler database.
 *
 */

public class ProfilerDB {
	/** where is the data saved */
	private static final String PROFILER_FILE = "data/profilerdata.xml";

	private Collection data, datainfo;
	private int id;

	public ProfilerDB() {

		data = new LinkedList(); // create an empty database
		datainfo = new LinkedList();
		try {
			ProfilerIO.loadProfilerData(PROFILER_FILE, data, datainfo);
		} catch(Exception ignored){ }

		id = getNextID();

		// this will also make the info-set to be created
		setDesc("  (write some words about the latest changes here)");
	}


	/**
	 * save the profiling database to disk
	 * @return true if everything worked fine, false otherwise
	 */
	public boolean save() {
		try {
			ProfilerIO.saveProfilerData(PROFILER_FILE, data, datainfo);
			return true;
		} catch(Exception exx) {
			JDDConsole.out.println("Error when saeving the database: " + exx.getMessage() );
		}
		return false;
	}

	/**
	 * find a specific dataset
	 *
	 */
	public ProfiledData getDataset(String name) {
		for(Iterator it = data.iterator(); it.hasNext(); ) {
			ProfiledData pd = (ProfiledData) it.next();
			if(pd.name.equals(name)) return pd;
		}

		// non found, create one:

		ProfiledData pd = new ProfiledData(name, 10);
		data.add(pd);
		return pd;
	}


	/** Get the differnce in time or since between two runs */
	public double getChange(int id1, int id2, boolean for_time) {

		double tsum = 0, nsum = 0;
		for(Iterator it = data.iterator(); it.hasNext(); ) {
			ProfiledData pd = (ProfiledData) it.next();
			int first = pd.findIndexForId(id1);
			int second= pd.findIndexForId(id2);
			if(first != -1 && second != -1) {
				if(for_time) {
					tsum += pd.times[first];
					nsum += pd.times[second];
				} else {
					tsum += pd.times[first];
					nsum += pd.times[second];
				}
			}
		}
		return (nsum == 0) ? 0 : tsum / nsum -1;
	}


	// -------------------------------------------------------------
	/** return the next avialable ID */
	private int getNextID() {
		int ret = 0;
		for(Iterator it = datainfo.iterator(); it.hasNext(); ) {
			ProfilingInfo pi = (ProfilingInfo) it.next();
			if(pi.id >= ret) ret = pi.id + 1;
		}
		return ret;
	}

	/** get a profiling-info set by its id */
	private ProfilingInfo getInfo(int id) {
		ProfilingInfo pi = findInfoById(id);

		if(pi == null) {			// not found, create a new one!
			pi = new ProfilingInfo();
			pi.id = id;
			pi.date = TimeUtility.getShortTimeString();
			pi.desc = "(empty)";
			datainfo.add(pi);
		}
		return pi;
	}

	/** find profiling-data by its id */
	public ProfilingInfo findInfoById(int id) {
		for(Iterator it = datainfo.iterator(); it.hasNext(); ) {
			ProfilingInfo pi = (ProfilingInfo) it.next();
			if(pi.id == id) return pi;
		}
		return null; // not found
	}
	// -------------------------------------------------------------

	/** set the descrption of the current  run */
	public void setDesc(String desc) {
		// this will return the current info-set
		ProfilingInfo pi = getInfo(id);
		pi.desc = desc;
	}

	/** get the descrption of the current  run */
	public String getDesc() {
		ProfilingInfo pi = getInfo(id);
		return pi.desc;
	}
	/** insert a new post */
	public void insert(String name, int time, long mem) {
		ProfiledData pd = getDataset(name); // find it
		pd.insert(id, time, mem);		// insert it with the current ID
	}

	public int getMyId() {return id; }


	// -------------------------------------------------------------
	/**
	 * Write the profiled data to stdout
	 *
	 */
	public void dump() {
		JDDConsole.out.println("The profiler database contains the following measures:");
		for(Iterator it = data.iterator(); it.hasNext(); ) {
			ProfiledData pd = (ProfiledData) it.next();
			pd.dump();
		}
		JDDConsole.out.println();

		JDDConsole.out.println("Each run is identified by a unique ID.");
		for(Iterator it = datainfo.iterator(); it.hasNext(); ) {
			ProfilingInfo pi = (ProfilingInfo) it.next();
			pi.dump();
		}
		JDDConsole.out.println();

	}
}


