package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;

import java.nio.ByteBuffer;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{

	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}

	private short calculateChecksum(IPv4 packet) {
		short originalChecksum = packet.getChecksum();
		packet.resetChecksum();
		byte[] serialized  = packet.serialize();
		ByteBuffer bb = ByteBuffer.wrap(serialized);

		byte version = bb.get();
		byte headerLength = (byte) (version & 0xf);
		version = (byte) ((version >> 4) & 0xf);
		byte diffServ = bb.get();
		short totalLength = bb.getShort();
		short identification = bb.getShort();
		short sscratch = bb.getShort();
		byte flags = (byte) ((sscratch >> 13) & 0x7);
		short fragmentOffset = (short) (sscratch & 0x1fff);
		short ttl = bb.get();
		byte protocol = bb.get();
		short checksum = bb.getShort();

		packet.setChecksum(originalChecksum);
		return checksum;
	}

	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	private IPv4 getIPv4Packet(Ethernet etherPacket) {
		return (IPv4)etherPacket.getPayload();
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param ipv4Packet the IPv4 packet that was received.
	 * @return  true on same otherwise false.
     */
	private boolean checkIPv4HeaderChecksum(IPv4 ipv4Packet) {
		// check the checksum
		short originalChecksum = ipv4Packet.getChecksum();
		short calculatedChecksum = calculateChecksum(ipv4Packet);

		return originalChecksum == calculatedChecksum;
	}

	private byte decreaseTTL(IPv4 ipv4Packet) {
		byte ttl = (byte)(ipv4Packet.getTtl() - 1);
		// Set new TTL
		ipv4Packet.setTtl(ttl);
		// Recalculate the checksum
		ipv4Packet.resetChecksum();
		ipv4Packet.setChecksum(calculateChecksum(ipv4Packet));

		return ttl;
	}

	private boolean isRouterInterface(IPv4 ipv4Packet) {
		for(Iface iface : this.getInterfaces().values()) {
			if(ipv4Packet.getDestinationAddress() == iface.getIpAddress()) {
				return true;
			}
		}
		return false;
	}

	private RouteEntry dstLookUp(IPv4 ipv4Packet) {
		return routeTable.lookup(ipv4Packet.getDestinationAddress());
	}

	private ArpEntry findDstArp(RouteEntry dst, IPv4 ipv4Packet) {
		ArpEntry dstArp;
		if(dst.getGatewayAddress() == IPv4.toIPv4Address("0.0.0.0")) {
			dstArp = arpCache.lookup(ipv4Packet.getDestinationAddress());
		} else {
			dstArp = arpCache.lookup(dst.getGatewayAddress());
		}
		return dstArp;
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
                etherPacket.toString().replace("\n", "\n\t"));
		// 1st: Do type checking. Drop ethernet packets other than IPv4
		if(etherPacket.getEtherType() != Ethernet.TYPE_IPv4) {
			System.out.println("Not a IPv4 packet! Drop it");
			return;
		}
		// check the checksum
		IPv4 ipv4Packet = getIPv4Packet(etherPacket);
		// Drop the packet if the checksum does not match
		if(!checkIPv4HeaderChecksum(ipv4Packet)) {
		    System.out.println("Packet checksum does not match");
			return;
		}
		// Drop the packet if TTL == 0
		if(decreaseTTL(ipv4Packet) == 0) {
			return;
		}
		// Check dstIp is one of the router interface
		if(isRouterInterface(ipv4Packet)) {
			return;
		}
       // Find the longest prefix
    	RouteEntry dst = dstLookUp(ipv4Packet);
        if(dst == null) {
        	System.out.println("No dest for ip: " + IPv4.fromIPv4Address(ipv4Packet.getDestinationAddress()));
        	return;
		}
        System.out.println(dst);
        // find next hop
		ArpEntry dstArp = findDstArp(dst, ipv4Packet);
		// Drop the packet if cannot find the arp destination
		if(dstArp == null) {
			System.out.println("cannot find dst arp entry");
			return;
		}
		// modify src and dst mac
        etherPacket.setSourceMACAddress(dst.getInterface().getMacAddress().toBytes());
		etherPacket.setDestinationMACAddress(dstArp.getMac().toBytes());
		// send out the packet
		sendPacket(etherPacket, dst.getInterface());
		// print out sent packet
		System.out.println("sent packet: "+ etherPacket);
	}
}
