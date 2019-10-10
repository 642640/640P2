package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device {
    private static class CacheNode {
        private Integer TTL;
        private Iface iface;
        private final int MAX_TTL;

        CacheNode(int MAX_TTL, Iface iface) {
            this.MAX_TTL = MAX_TTL;
            this.TTL = MAX_TTL;
            this.iface = iface;
        }

        synchronized Iface getIface() {
            synchronized (iface) {
                return iface;
            }
        }

        synchronized void setIface(Iface iface) {
            synchronized (iface) {
                this.iface = iface;
            }
        }

        synchronized int decreaseTTL() {
            synchronized (TTL) {
                TTL -= 1;
                // return decreased TTL
                return TTL;
            }
        }

        synchronized void updateTTL() {
            synchronized (TTL) {
                TTL = MAX_TTL;
            }
        }
    }

    // Use Hashtable to ensure thread safety
    // Otherwise spinning lock or mutex lock is acceptable
    private ConcurrentHashMap<String, CacheNode> forwardTable;

    /**
     * Creates a router for a specific host.
     *
     * @param host hostname for the router
     */
    public Switch(String host, DumpFile logfile) {
        super(host, logfile);
        // Initialize the forward table
        forwardTable = new ConcurrentHashMap<>();
        // Create a timer task
        new Timer().schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        // Return when forward table is empty
                        if(forwardTable.isEmpty()) {
                            return;
                        }
                        // Get the iterator
                        Iterator<Map.Entry<String, CacheNode>> it;
                        it = forwardTable.entrySet().iterator();
                        // For each the whole table
                        while (it.hasNext()) {
                            Map.Entry<String, CacheNode> entry = it.next();
                            // Decrease TTL
                            int TTL = entry.getValue().decreaseTTL();
                            // Remove entry if TTL is <= 0.
                            if (TTL <= 0) {
                                System.out.println(entry.getKey() + " removed!");
                                it.remove();
                            }
                        }
                    }
                },
                0,
                1000
        );
    }

    /**
     * Broadcast an Ethernet packet to all interfaces.
     *
     * @param etherPacket the Ethernet packet that was received
     */
    private void broadcast(Ethernet etherPacket) {
        for (Map.Entry<String, Iface> entry : interfaces.entrySet()) {
            sendPacket(etherPacket, entry.getValue());
        }
    }

    /**
     * Update the forward table.
     *
     * @param etherPacket      the recieved ethernet packet
     * @param inIface          the interface on which the packet was received
     */
    private void updateFowradTable(Ethernet etherPacket, Iface inIface) {
        // Grab src MAC
        String srcMAC = etherPacket.getSourceMAC().toString();
        // Fill up forward table
        if (forwardTable.containsKey(srcMAC)) {
            // Get cache
            CacheNode cache = forwardTable.get(srcMAC);
            // Update TTL
            cache.updateTTL();
            // Update iface number
            cache.setIface(inIface);
        } else {
            // record the MAC and iface
            int MAX_TTL = 15;
            forwardTable.put(srcMAC, new CacheNode(MAX_TTL, inIface));
            System.out.println("Add " + srcMAC + " into forward table");
        }
    }

    private void doSwitch(Ethernet etherPacket) {
        // Fetch dest MAC address
        String destMAC = etherPacket.getDestinationMAC().toString();
        // Look up the forward table
        if (forwardTable.containsKey(destMAC)) {
            System.out.println("Found " + destMAC + " in forward table");
            CacheNode cache = forwardTable.get(destMAC);
            // send packet
            sendPacket(etherPacket, cache.getIface());
        } else {
            // broadcast packet to all interface
            broadcast(etherPacket);
        }
    }

    /**
     * Handle an Ethernet packet received on a specific interface.
     *
     * @param etherPacket the Ethernet packet that was received
     * @param inIface     the interface on which the packet was received
     */
    public void handlePacket(Ethernet etherPacket, Iface inIface) {
        System.out.println("*** -> Received packet: " +
                etherPacket.toString().replace("\n", "\n\t"));
        // Update forward table
        updateFowradTable(etherPacket, inIface);
        // Switch
        doSwitch(etherPacket);
    }
}
