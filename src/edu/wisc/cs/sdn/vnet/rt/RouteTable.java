package edu.wisc.cs.sdn.vnet.rt;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.floodlightcontroller.packet.IPv4;

import edu.wisc.cs.sdn.vnet.Iface;

/**
 * Route table for a router.
 * @author Aaron Gember-Jacobson
 */
public class RouteTable 
{
    /*
	static class PrefixTree {
		class TreeNode {
			private String prefix;
			private HashMap<String, TreeNode> nodes;
			private ArrayList<RouteEntry> entries;

			TreeNode(String prefix) {
				this.prefix = prefix;
				nodes = new HashMap<>();
				entries = new ArrayList<>();
			}

			public String getPrefix() {
				return prefix;
			}

			public void setPrefix(String prefix) {
				this.prefix = prefix;
			}

			public HashMap<String, TreeNode> getNodes() {
				return nodes;
			}

			public void setNodes(HashMap<String, TreeNode> nodes) {
				this.nodes = nodes;
			}

			public ArrayList<RouteEntry> getEntries() {
				return entries;
			}

			public void setEntries(ArrayList<RouteEntry> entries) {
				this.entries = entries;
			}
		}

		private TreeNode root = new TreeNode("");

		public void add(String ip, RouteEntry entry) {
			String[] e = ip.split("\\.");
			// When tree is empty
			TreeNode curr = root;
			for (int i = 0; i < 4; i++) {
				HashMap<String, TreeNode> nodes = curr.getNodes();

				if (!nodes.containsKey(e[i])) {
					TreeNode newNode = new TreeNode(e[i]);
					nodes.put(e[i], newNode);
					curr = newNode;
					curr.getEntries().add(entry);
				} else {
					curr = nodes.get(e[i]);
					curr.getEntries().add(entry);
				}
			}
		}
		private int getBit(byte b, int pos) {
			return (b >> pos) & 1;
		}

		private int countIdenticalBits(byte a, byte b) {
			int count = 0;
			for(int i = 7; i >= 0; i--) {
				if(getBit(a, i) == getBit(b, i)){
					count++;
				} else {
					break;
				}
			}

			return count;
		}

		private RouteEntry compareByBits(HashMap<String, TreeNode> map, String nextPrefix, int level) {
			RouteEntry result = null;
			int max_bits = 0;
			return null;
		}

		private RouteEntry find(String ip) {
			if (root == null) {
				return null;
			}

			String[] e = ip.split("\\.");
			// When tree is empty
			TreeNode curr = root;
			int level = 0;
			for (; level < 4; level++) {
				if (!curr.getNodes().containsKey(e[level])) {
					break;
				} else {
					curr = curr.getNodes().get(e[level]);
				}
			}
			// no matching prefix
			if (curr == root) {
				return null;
			}

			if (curr.getEntries().size() != 1) {
			    compareByBits(curr.getNodes(), e[level]);
				return null;
			}

			return curr.getEntries().get(0);
		}
	}
     */

	/** Entries in the route table */
	private List<RouteEntry> entries;
	// private PrefixTree prefixTree;
	
	/**
	 * Initialize an empty route table.
	 */
	RouteTable()
	{
		this.entries = new LinkedList<>();
		// this.prefixTree = new PrefixTree();
	}

	private int getBit(int i, int pos) {
		return (i >> pos) & 1;
	}

	private int countIdenticalBits(int a, int b, int length) {
		int count = 0;
		for(int i = 31; i >= (32 - length); i--) {
			if(getBit(a, i) == getBit(b, i)){
				count++;
			} else {
				break;
			}
		}

		return count;
	}

	/**
	 * Lookup the route entry that matches a given IP address.
	 * @param ip IP address
	 * @return the matching route entry, null if none exists
	 */
	public RouteEntry lookup(int ip)
	{
		synchronized(this.entries)
        {
			// return prefixTree.find(IPv4.fromIPv4Address(ip));
            RouteEntry entry = null;
            ArrayList<RouteEntry> resultSet = new ArrayList<RouteEntry>();
			// find possible entries
            for(RouteEntry e : entries) {
            	int maskedDest = e.getDestinationAddress() & e.getMaskAddress();
				int maskedIp = ip & e.getMaskAddress();

				if(maskedDest == maskedIp) {
					resultSet.add(e);
				}
			}
			// if there is no match return 0
            if(resultSet.size() == 0) {
            	return null;
			} else if(resultSet.size() == 1) {
            	return resultSet.get(0);
			}
			// use longest prefix to resolve
			int max_bits_matched = 0;
            for(RouteEntry e : resultSet) {
            	int maskedDest = e.getDestinationAddress() & e.getMaskAddress();
                int tmp = countIdenticalBits(ip, maskedDest, e.getMaskedLength());
                System.out.println("Identical bits: " + tmp);
                if(tmp > max_bits_matched) {
					entry = e;
					max_bits_matched = tmp;
				}
			}

            return entry;
		}
	}
	
	/**
	 * Populate the route table from a file.
	 * @param filename name of the file containing the static route table
	 * @param router the route table is associated with
	 * @return true if route table was successfully loaded, otherwise false
	 */
	public boolean load(String filename, Router router)
	{
		// Open the file
		BufferedReader reader;
		try 
		{
			FileReader fileReader = new FileReader(filename);
			reader = new BufferedReader(fileReader);
		}
		catch (FileNotFoundException e) 
		{
			System.err.println(e.toString());
			return false;
		}
		
		while (true)
		{
			// Read a route entry from the file
			String line = null;
			try 
			{ line = reader.readLine(); }
			catch (IOException e) 
			{
				System.err.println(e.toString());
				try { reader.close(); } catch (IOException f) {};
				return false;
			}
			
			// Stop if we have reached the end of the file
			if (null == line)
			{ break; }
			
			// Parse fields for route entry
			String ipPattern = "(\\d+\\.\\d+\\.\\d+\\.\\d+)";
			String ifacePattern = "([a-zA-Z0-9]+)";
			Pattern pattern = Pattern.compile(String.format(
                        "%s\\s+%s\\s+%s\\s+%s", 
                        ipPattern, ipPattern, ipPattern, ifacePattern));
			Matcher matcher = pattern.matcher(line);
			if (!matcher.matches() || matcher.groupCount() != 4)
			{
				System.err.println("Invalid entry in routing table file");
				try { reader.close(); } catch (IOException f) {};
				return false;
			}

			int dstIp = IPv4.toIPv4Address(matcher.group(1));
			if (0 == dstIp)
			{
				System.err.println("Error loading route table, cannot convert "
						+ matcher.group(1) + " to valid IP");
				try { reader.close(); } catch (IOException f) {};
				return false;
			}
			
			int gwIp = IPv4.toIPv4Address(matcher.group(2));
			
			int maskIp = IPv4.toIPv4Address(matcher.group(3));
			if (0 == maskIp)
			{
				System.err.println("Error loading route table, cannot convert "
						+ matcher.group(3) + " to valid IP");
				try { reader.close(); } catch (IOException f) {};
				return false;
			}
			
			String ifaceName = matcher.group(4).trim();
			Iface iface = router.getInterface(ifaceName);
			if (null == iface)
			{
				System.err.println("Error loading route table, invalid interface "
						+ matcher.group(4));
				try { reader.close(); } catch (IOException f) {};
				return false;
			}
			
			// Add an entry to the route table
			this.insert(dstIp, gwIp, maskIp, iface);
		}
	
		// Close the file
		try { reader.close(); } catch (IOException f) {};
		return true;
	}

	private int getMaskLength(int n) {
		int count = 0;
		while (n != 0) {
			n = n & (n - 1);
			count++;
		}
		return count;
	}

	/**
	 * Add an entry to the route table.
	 * @param dstIp destination IP
	 * @param gwIp gateway IP
	 * @param maskIp subnet mask
	 * @param iface router interface out which to send packets to reach the 
	 *        destination or gateway
	 */
	public void insert(int dstIp, int gwIp, int maskIp, Iface iface)
	{
		RouteEntry entry = new RouteEntry(dstIp, gwIp, maskIp, iface, getMaskLength(maskIp));
        synchronized(this.entries)
        { 
            this.entries.add(entry);
        }
	}
	
	/**
	 * Remove an entry from the route table.
	 * @param dstIp destination IP of the entry to remove
     * @param maskIp subnet mask of the entry to remove
     * @return true if a matching entry was found and removed, otherwise false
	 */
	public boolean remove(int dstIp, int maskIp)
	{ 
        synchronized(this.entries)
        {
            RouteEntry entry = this.find(dstIp, maskIp);
            if (null == entry)
            { return false; }
            this.entries.remove(entry);
        }
        return true;
    }
	
	/**
	 * Update an entry in the route table.
	 * @param dstIp  destination IP of the entry to update
     * @param maskIp subnet mask of the entry to update
	 * @param gwIp   new gateway IP address for matching entry
	 * @param iface  new router interface for matching entry
     * @return true if a matching entry was found and updated, otherwise false
	 */
	public boolean update(int dstIp, int maskIp, int gwIp, 
            Iface iface)
	{
        synchronized(this.entries)
        {
            RouteEntry entry = this.find(dstIp, maskIp);
            if (null == entry)
            { return false; }
            entry.setGatewayAddress(gwIp);
            entry.setInterface(iface);
        }
        return true;
	}

    /**
	 * Find an entry in the route table.
	 * @param dstIp destination IP of the entry to find
     * @param maskIp subnet mask of the entry to find
     * @return a matching entry if one was found, otherwise null
	 */
    private RouteEntry find(int dstIp, int maskIp)
    {
        synchronized(this.entries)
        {
            for (RouteEntry entry : this.entries)
            {
                if ((entry.getDestinationAddress() == dstIp)
                    && (entry.getMaskAddress() == maskIp)) 
                { return entry; }
            }
        }
        return null;
    }

	public String toString()
	{
        synchronized(this.entries)
        { 
            if (0 == this.entries.size())
            { return " WARNING: route table empty"; }
            
            String result = "Destination\tGateway\t\tMask\t\tIface\n";
            for (RouteEntry entry : entries)
            { result += entry.toString()+"\n"; }
		    return result;
        }
	}
}
