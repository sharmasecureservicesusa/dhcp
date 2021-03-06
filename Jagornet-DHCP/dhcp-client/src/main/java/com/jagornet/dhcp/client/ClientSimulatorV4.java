/*
 * Copyright 2009-2014 Jagornet Technologies, LLC.  All Rights Reserved.
 *
 * This software is the proprietary information of Jagornet Technologies, LLC. 
 * Use is subject to license terms.
 *
 */

/*
 *   This file ClientSimulatorV4.java is part of Jagornet DHCP.
 *
 *   Jagornet DHCP is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Jagornet DHCP is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Jagornet DHCP.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.jagornet.dhcp.client;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.DatagramChannel;
import org.jboss.netty.channel.socket.DatagramChannelFactory;
import org.jboss.netty.channel.socket.nio.NioDatagramChannelFactory;
import org.jboss.netty.handler.execution.ExecutionHandler;
import org.jboss.netty.handler.execution.OrderedMemoryAwareThreadPoolExecutor;
import org.jboss.netty.handler.logging.LoggingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jagornet.dhcp.core.message.DhcpV4Message;
import com.jagornet.dhcp.core.option.v4.DhcpV4HostnameOption;
import com.jagornet.dhcp.core.option.v4.DhcpV4MsgTypeOption;
import com.jagornet.dhcp.core.option.v4.DhcpV4RequestedIpAddressOption;
import com.jagornet.dhcp.core.option.v4.DhcpV4ServerIdOption;
import com.jagornet.dhcp.core.util.DhcpConstants;
import com.jagornet.dhcp.core.util.Util;

/**
 * A test client that sends discover/request/release messages 
 * to a DHCPv4 server via unicast, as if sent via a relay.
 * 
 * @author A. Gregory Rabil
 */
@ChannelHandler.Sharable
public class ClientSimulatorV4 extends SimpleChannelUpstreamHandler
{
	private static Logger log = LoggerFactory.getLogger(ClientSimulatorV4.class);

	protected Random random = new Random();
    protected Options options = new Options();
    protected CommandLineParser parser = new BasicParser();
    protected HelpFormatter formatter;
    
	protected InetAddress DEFAULT_ADDR;
    protected InetAddress serverAddr;
    protected int serverPort = DhcpConstants.V4_SERVER_PORT;
    protected InetAddress clientAddr;
    protected int clientPort = DhcpConstants.V4_SERVER_PORT;	// the test client acts as a relay
    protected boolean rapidCommit = false;
    protected boolean sendRelease = false;
    protected boolean sendHostname = false;
    protected int numRequests = 100;
    protected AtomicInteger discoversSent = new AtomicInteger();
    protected AtomicInteger offersReceived = new AtomicInteger();
    protected AtomicInteger requestsSent = new AtomicInteger();
    protected AtomicInteger acksReceived = new AtomicInteger();
    protected AtomicInteger releasesSent = new AtomicInteger();
    protected int successCnt = 0;
    protected long startTime = 0;    
    protected long endTime = 0;
    protected long timeout = 0;
    protected int poolSize = 0;
    protected int threadPoolSize = 0;
    protected int requestRate = 0;
    protected CountDownLatch doneLatch = null;

    protected InetSocketAddress server = null;
    protected InetSocketAddress client = null;
    
    protected DatagramChannel channel = null;	
	//protected ExecutorService executor = Executors.newCachedThreadPool();
    protected ExecutorService executor = null;
	
	protected Map<BigInteger, ClientMachine> clientMap =
			Collections.synchronizedMap(new HashMap<BigInteger, ClientMachine>());

    /**
     * Instantiates a new test client.
     *
     * @param args the args
     * @throws Exception the exception
     */
    public ClientSimulatorV4(String[] args) throws Exception 
    {
    	DEFAULT_ADDR = InetAddress.getLocalHost();
    	
        setupOptions();

        if(!parseOptions(args)) {
            formatter = new HelpFormatter();
            String cliName = this.getClass().getName();
            formatter.printHelp(cliName, options);
            System.exit(0);
        }
        
        log.info("Starting ClientSimulatorV4 with threadPoolSize=" + threadPoolSize);
        if (threadPoolSize <= 0) {
        	executor = Executors.newCachedThreadPool();
        }
        else {
        	executor = Executors.newFixedThreadPool(threadPoolSize);
        }
        try {
			start();
		} 
        catch (Exception ex) {
			ex.printStackTrace();
		}
    }
    
	/**
	 * Setup options.
	 */
	private void setupOptions()
    {
		Option numOption = new Option("n", "number", true,
										"Number of client requests to send" +
										" [" + numRequests + "]");
		options.addOption(numOption);
		
        Option caOption = new Option("ca", "clientaddress", true,
        								"Address of DHCPv4 Client (relay)" +
        								" [" + DEFAULT_ADDR + "]");		
        options.addOption(caOption);
		
        Option saOption = new Option("sa", "serveraddress", true,
        								"Address of DHCPv4 Server" +
        								" [" + DEFAULT_ADDR + "]");		
        options.addOption(saOption);

        Option cpOption = new Option("cp", "clientport", true,
        							  "Client Port Number" +
        							  " [" + clientPort + "]");
        options.addOption(cpOption);

        Option spOption = new Option("sp", "serverport", true,
        							  "Server Port Number" +
        							  " [" + serverPort + "]");
        options.addOption(spOption);
        
        Option rOption = new Option("r", "rapidcommit", false,
        							"Send rapid-commit Solicit requests");
        options.addOption(rOption);
        
        Option toOption = new Option("to", "timeout", true,
        							"Timeout");
        options.addOption(toOption);
        
        Option psOption = new Option("ps", "poolsize", true,
        							"Size of the pool configured on the server; wait for release after this many requests");
        options.addOption(psOption);
        
        Option tpsOption = new Option("tps", "threadpoolsize", true,
        							"Size of the thread pool used by the client");
        options.addOption(tpsOption);
        
        Option xOption = new Option("x", "release", false,
        							"Send release");
        options.addOption(xOption);
        
        Option hOption = new Option("h", "hostname", false,
        							"Send hostname");
        options.addOption(hOption);
        
        Option rrOption = new Option("rr","requestrate", true,
        							"Request rate per second");
        options.addOption(rrOption);
        
        Option helpOption = new Option("?", "help", false, "Show this help page.");
        
        options.addOption(helpOption);
    }

	
	protected int parseIntegerOption(String opt, String str, int defval) {
    	int val = defval;
    	try {
    		val = Integer.parseInt(str);
    	}
    	catch (NumberFormatException ex) {
    		System.err.println("Invalid " + opt + " '" + str +
    							"' using default: " + defval +
    							" Exception=" + ex);
    		val = defval;
    	}
    	return val;
	}
	
	protected InetAddress parseIpAddressOption(String opt, String str, InetAddress defaddr) {
    	InetAddress addr = defaddr;
    	try {
    		addr = InetAddress.getByName(str);
    	}
    	catch (UnknownHostException ex) {
    		System.err.println("Invalid " + opt + " address: '" + str +
    							"' using default: " + defaddr +
    							" Exception=" + ex);
    		addr = defaddr;
    	}
    	return addr;
	}
	
    /**
     * Parses the options.
     * 
     * @param args the args
     * 
     * @return true, if successful
     */
    protected boolean parseOptions(String[] args)
    {
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("?")) {
                return false;
            }
            if (cmd.hasOption("n")) {
            	numRequests = 
            			parseIntegerOption("num requests", cmd.getOptionValue("n"), 100);
            }
            clientAddr = DEFAULT_ADDR;
            if (cmd.hasOption("ca")) {
            	clientAddr = 
            			parseIpAddressOption("client", cmd.getOptionValue("ca"), DEFAULT_ADDR);
            }
            serverAddr = DEFAULT_ADDR;
            if (cmd.hasOption("sa")) {
            	serverAddr = 
            			parseIpAddressOption("server", cmd.getOptionValue("sa"), DEFAULT_ADDR);
            }
            if (cmd.hasOption("cp")) {
            	clientPort = 
            			parseIntegerOption("client port", cmd.getOptionValue("cp"), 
            								DhcpConstants.V6_CLIENT_PORT);
            }
            if (cmd.hasOption("sp")) {
            	serverPort = 
            			parseIntegerOption("server port", cmd.getOptionValue("sp"), 
            								DhcpConstants.V6_SERVER_PORT);
            }
            if (cmd.hasOption("r")) {
            	rapidCommit = true;
            }
            if (cmd.hasOption("to")) {
            	timeout = 
            			parseIntegerOption("timeout", cmd.getOptionValue("to"), 0);
            }
            if (cmd.hasOption("ps")) {
            	poolSize = 
            			parseIntegerOption("address pool size configured on server", cmd.getOptionValue("ps"), 0);
            }
            if (cmd.hasOption("tps")) {
            	threadPoolSize = 
            			parseIntegerOption("thread pool size used by client", cmd.getOptionValue("tps"), 0);
            }
            if (cmd.hasOption("x")) {
            	sendRelease = true;
            }
            if (cmd.hasOption("h")) {
            	sendHostname = true;
            }
            if (cmd.hasOption("rr")) {
            	requestRate = 
            			parseIntegerOption("request rate per second", cmd.getOptionValue("rr"), 0);
            }
            
            if (poolSize > 0 && !sendRelease) {
            	System.err.println("Must specify -x/--release when using -ps/--poolsize");
            	return false;
            }
        }
        catch (ParseException pe) {
            System.err.println("Command line option parsing failure: " + pe);
            return false;
		}
        return true;
    }
    
    /**
     * Start sending DHCPv4 DISCOVERs.
     */
    public void start()
    {
    	DatagramChannelFactory factory = 
    		new NioDatagramChannelFactory(Executors.newCachedThreadPool());
    	
    	server = new InetSocketAddress(serverAddr, serverPort);
    	client = new InetSocketAddress(clientPort);
    	
		ChannelPipeline pipeline = Channels.pipeline();
        pipeline.addLast("logger", new LoggingHandler());
        pipeline.addLast("encoder", new DhcpV4ChannelEncoder());
        pipeline.addLast("decoder", new DhcpV4ChannelDecoder(client, false));
        pipeline.addLast("executor", new ExecutionHandler(
        		new OrderedMemoryAwareThreadPoolExecutor(16, 1048576, 1048576)));
        pipeline.addLast("handler", this);
    	
        channel = factory.newChannel(pipeline);
    	channel.bind(client);
    	
    	if (requestRate <= 0) {
    		// spin up requests as fast as possible
	    	for (int i=1; i<=numRequests; i++) {
	    		log.debug("Executing client " + i);
	    		executor.execute(new ClientMachine(i));
	    	}
    	}
    	else {
	    	// spin up requests at the given rate per second
	    	Thread requestThread = new Thread() {
	    		@Override
	    		public void run() {
	    	    	long before = System.currentTimeMillis();
	    	    	log.debug("Spinning up " + numRequests + " clients at " + before);
	    	    	for (int i=1; i<=numRequests; i++) {
	    	    		log.debug("Executing client " + i);
	    	    		executor.execute(new ClientMachine(i));
	    	    		// if not the last request, see if on request rate boundary
	    	    		if ((i < numRequests) && (i % requestRate == 0)) {
	    	    			long now = System.currentTimeMillis();
	    	    			long diff = now - before;
	    	    			// if less than one second since starting last batch
	    	    			if (diff < 1000) {
	    	    				long wait = 1000 - diff;
	    	    				try {
	    	    					log.debug("Waiting " + wait + "ms to honor requestRate=" + requestRate);
	    							Thread.sleep(wait);
	    							before = System.currentTimeMillis();
	    						} catch (InterruptedException e) {
	    							// TODO Auto-generated catch block
	    							e.printStackTrace();
	    						}
	    	    			}
	    	    		}
	    	    	}
	    		}
	    	};
	    	requestThread.start();
    	}
    	
    	doneLatch = new CountDownLatch(numRequests);
    	try {
    		if (timeout <= 0) {
    			log.info("Waiting for completion");
    			doneLatch.await();
    		}
    		else {
	    		log.info("Waiting total of " + timeout + " seconds for completion");
				doneLatch.await(timeout, TimeUnit.SECONDS);
    		}
		} catch (InterruptedException e) {
			log.warn("Waiting interrupted");
			System.err.println("Interrupted");
		}
    	
		endTime = System.currentTimeMillis();
    	
		log.info("Complete: discoversSent=" + discoversSent +
				" offersReceived=" + offersReceived +
				" requestsSent=" + requestsSent +
				" acksReceived=" + acksReceived +
				" releasesSent=" + releasesSent +
				" elapsedTime=" + (endTime - startTime) + "ms");

    	log.info("Shutting down executor...");
    	executor.shutdownNow();
    	log.info("Closing channel...");
    	channel.close();
    	log.info("Done.");
    	if ((discoversSent.get() == offersReceived.get()) &&
    			(requestsSent.get() == acksReceived.get()) &&
    			(!sendRelease || (releasesSent.get() == numRequests))) {
    		
    		log.info("System exit 0 (success)");
    		System.exit(0);
    	}
    	else {
    		log.info("System exit 1 (failure)");
    		System.exit(1);
    	}
    }

    /**
     * The Class ClientMachine.
     */
    class ClientMachine implements Runnable, ChannelFutureListener
    {
    	DhcpV4Message msg;
    	int id;
    	byte[] mac;
    	BigInteger key;
    	Semaphore replySemaphore;
    	DhcpV4Message offerMsg;
    	DhcpV4Message ackMsg;
    	boolean retry;
    	
    	/**
	     * Instantiates a new client machine.
	     *
	     * @param msg the msg
	     * @param server the server
	     */
	    public ClientMachine(int id) {
    		this.id = id;
    		this.mac = buildChAddr(id);
    		this.key = new BigInteger(mac);
    		this.replySemaphore = new Semaphore(1);
    		this.retry = true;	// configure to retry
    	}
		
		/* (non-Javadoc)
		 * @see java.lang.Runnable#run()
		 */
		@Override
		public void run() {
			if (poolSize > 0) {
				synchronized (clientMap) {
					if (poolSize <= clientMap.size()) {
						try {
							log.info("Waiting for release...");
							clientMap.wait();
						} 
						catch (InterruptedException ex) {
							log.error("Interrupted", ex);
						}
					}
					clientMap.put(key, this);
				}
			}
			else {
				clientMap.put(key, this);
			}
			discover();
		}
		
		public void discover() {
			msg = buildDiscoverMessage(mac); 
			ChannelFuture future = channel.write(msg, server);
			future.addListener(this);
			replySemaphore.drainPermits();
			waitForOffer();
		}
	    
	    public void waitForOffer() {
	    	try {
				if (!replySemaphore.tryAcquire(2, TimeUnit.SECONDS)) {
					if (retry) {
						retry = false;
						log.warn("Discover timeout after 2 seconds, retrying...");
						discover();
					}
				}
				else {
					request();
				}
			} catch (InterruptedException e) {
				log.warn(e.getMessage());
			}
	    }
	    
	    public void offerReceived(DhcpV4Message offerMsg) {
	    	this.offerMsg = offerMsg;
	    	replySemaphore.release();
	    }
		
		public void request() {
			if (offerMsg != null) {
	        	msg = buildRequestMessage(offerMsg);
				ChannelFuture future = channel.write(msg, server);
				future.addListener(this);
				waitForAck();
			}
			else {
				log.error("No offer to request!");
			}
		}
	    
	    public void waitForAck() {
	    	try {
	    		if (!replySemaphore.tryAcquire(2, TimeUnit.SECONDS)) {
	    			if (retry) {
	    				retry = false;
						log.warn("Request timeout after 2 seconds, retrying...");
	    				request();
	    			}
	    		}
	    		else {
	    			release();
	    		}
			} catch (InterruptedException e) {
				log.warn(e.getMessage());
			}
	    }
	    
	    public void ackReceived(DhcpV4Message ackMsg) {
	    	this.ackMsg = ackMsg;
	    	replySemaphore.release();
	    }
		
		public void release() {
			if (sendRelease) {
				if (ackMsg != null) {
		        	msg = buildReleaseMessage(ackMsg);
					ChannelFuture future = channel.write(msg, server);
					future.addListener(this);
				}
				else {
					log.error("No ack to release!");
				}
			}
			else {
				clientMap.remove(key);
				doneLatch.countDown();
			}
		}
		
		/* (non-Javadoc)
		 * @see org.jboss.netty.channel.ChannelFutureListener#operationComplete(org.jboss.netty.channel.ChannelFuture)
		 */
		@Override
		public void operationComplete(ChannelFuture future) throws Exception
		{
			if (future.isSuccess()) {
				if (startTime == 0) {
					startTime = System.currentTimeMillis();
					log.info("Starting at: " + startTime);
				}
				if (msg.getMessageType() == DhcpConstants.V4MESSAGE_TYPE_DISCOVER) {
					discoversSent.getAndIncrement();
					log.info("Successfully sent discover message mac=" + Util.toHexString(mac) +
							" cnt=" + discoversSent);
				}
				else if (msg.getMessageType() == DhcpConstants.V4MESSAGE_TYPE_REQUEST) {
					requestsSent.getAndIncrement();
					log.info("Successfully sent request message mac=" + Util.toHexString(mac) +
							" cnt=" + requestsSent);
				}
				else if (msg.getMessageType() == DhcpConstants.V4MESSAGE_TYPE_RELEASE) {
					releasesSent.getAndIncrement();
					log.info("Successfully sent release message mac=" + Util.toHexString(mac) +
							" cnt=" + releasesSent);
					clientMap.remove(key);
					if (poolSize > 0) {
						synchronized (clientMap) {
							clientMap.notify();
						}
					}
					doneLatch.countDown();
				}
			}
			else {
				log.error("Failed to send message id=" + msg.getTransactionId() +
						  ": " + future.getCause());
			}
		}
    }

    private byte[] buildChAddr(long id) {
        byte[] bid = BigInteger.valueOf(id).toByteArray();
        byte[] chAddr = new byte[6];
        chAddr[0] = (byte)0xde;
        chAddr[1] = (byte)0xb1;
        if (bid.length == 4) {
            chAddr[2] = bid[0];
            chAddr[3] = bid[1];
            chAddr[4] = bid[2];
            chAddr[5] = bid[3];
        }
        else if (bid.length == 3) {
	        chAddr[2] = 0;
	        chAddr[3] = bid[0];
	        chAddr[4] = bid[1];
	        chAddr[5] = bid[2];
        }
        else if (bid.length == 2) {
	        chAddr[2] = 0;
	        chAddr[3] = 0;
	        chAddr[4] = bid[0];
	        chAddr[5] = bid[1];
        }
        else if (bid.length == 1) {
	        chAddr[2] = 0;
	        chAddr[3] = 0;
	        chAddr[4] = 0;
	        chAddr[5] = bid[0];
        }
        return chAddr;
    }
    
    /**
     * Builds the discover message.
     * 
     * @return the  dhcp message
     */
    private DhcpV4Message buildDiscoverMessage(byte[] chAddr)
    {
        DhcpV4Message msg = new DhcpV4Message(null, new InetSocketAddress(serverAddr, serverPort));

        msg.setOp((short)DhcpConstants.V4_OP_REQUEST);
        msg.setTransactionId(random.nextLong());
        msg.setHtype((short)1);	// ethernet
        msg.setHlen((byte)6);
        msg.setChAddr(chAddr);
        msg.setGiAddr(clientAddr);	// look like a relay to the DHCP server
        
        DhcpV4MsgTypeOption msgTypeOption = new DhcpV4MsgTypeOption();
        msgTypeOption.setUnsignedByte((short)DhcpConstants.V4MESSAGE_TYPE_DISCOVER);
        
        msg.putDhcpOption(msgTypeOption);
        
        if (sendHostname) {
        	DhcpV4HostnameOption hostnameOption = new DhcpV4HostnameOption();
        	hostnameOption.setString("jagornet-clientsimv4-" + Util.toHexString(chAddr));
        	msg.putDhcpOption(hostnameOption);
        }
        
        return msg;
    }
    
    private DhcpV4Message buildRequestMessage(DhcpV4Message offer) {
    	
        DhcpV4Message msg = new DhcpV4Message(null, new InetSocketAddress(serverAddr, serverPort));

        msg.setOp((short)DhcpConstants.V4_OP_REQUEST);
        msg.setTransactionId(offer.getTransactionId());
        msg.setHtype((short)1);	// ethernet
        msg.setHlen((byte)6);
        msg.setChAddr(offer.getChAddr());
        msg.setGiAddr(clientAddr);	// look like a relay to the DHCP server
        
        DhcpV4MsgTypeOption msgTypeOption = new DhcpV4MsgTypeOption();
        msgTypeOption.setUnsignedByte((short)DhcpConstants.V4MESSAGE_TYPE_REQUEST);
        
        msg.putDhcpOption(msgTypeOption);
        
        if (sendHostname) {
        	DhcpV4HostnameOption hostnameOption = new DhcpV4HostnameOption();
        	hostnameOption.setString("jagornet-clientsimv4-" + Util.toHexString(offer.getChAddr()));
        	msg.putDhcpOption(hostnameOption);
        }
        
        DhcpV4RequestedIpAddressOption reqIpOption = new DhcpV4RequestedIpAddressOption();
        reqIpOption.setIpAddress(offer.getYiAddr().getHostAddress());
        msg.putDhcpOption(reqIpOption);
        
        // MUST include serverId option for selecting state
        DhcpV4ServerIdOption serverIdOption = offer.getDhcpV4ServerIdOption();
        msg.putDhcpOption(serverIdOption);
        
        return msg;
    }
    
    private DhcpV4Message buildReleaseMessage(DhcpV4Message ack) {
    	
        DhcpV4Message msg = new DhcpV4Message(null, new InetSocketAddress(serverAddr, serverPort));

        msg.setOp((short)DhcpConstants.V4_OP_REQUEST);
        msg.setTransactionId(ack.getTransactionId());
        msg.setHtype((short)1);	// ethernet
        msg.setHlen((byte)6);
        msg.setChAddr(ack.getChAddr());
        msg.setGiAddr(clientAddr);	// look like a relay to the DHCP server
        msg.setCiAddr(ack.getYiAddr());
        
        DhcpV4MsgTypeOption msgTypeOption = new DhcpV4MsgTypeOption();
        msgTypeOption.setUnsignedByte((short)DhcpConstants.V4MESSAGE_TYPE_RELEASE);
        
        msg.putDhcpOption(msgTypeOption);
        
        return msg;
    }

	/*
	 * (non-Javadoc)
	 * @see org.jboss.netty.channel.SimpleChannelHandler#messageReceived(org.jboss.netty.channel.ChannelHandlerContext, org.jboss.netty.channel.MessageEvent)
	 */
	@Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception
    {
    	Object message = e.getMessage();
        if (message instanceof DhcpV4Message) {
            
            DhcpV4Message dhcpMessage = (DhcpV4Message) message;
//            if (log.isDebugEnabled())
//            	log.debug("Received: " + dhcpMessage.toStringWithOptions());
//            else
            	log.info("Received: " + dhcpMessage.toString());
            
            if (dhcpMessage.getMessageType() == DhcpConstants.V4MESSAGE_TYPE_OFFER) {
	            ClientMachine client = clientMap.get(new BigInteger(dhcpMessage.getChAddr()));
	            if (client != null) {
	            	offersReceived.getAndIncrement();
	            	client.offerReceived(dhcpMessage);
	            }
	            else {
	            	log.error("Received offer for client not found in map: mac=" + 
	            			Util.toHexString(dhcpMessage.getChAddr()));
	            }
            }
            else if (dhcpMessage.getMessageType() == DhcpConstants.V4MESSAGE_TYPE_ACK) {
	            ClientMachine client = clientMap.get(new BigInteger(dhcpMessage.getChAddr()));
	            if (client != null) {
	            	acksReceived.getAndIncrement();
	            	client.ackReceived(dhcpMessage);
	            }
	            else {
	            	log.error("Received ack for client not found in map: mac=" + 
	            			Util.toHexString(dhcpMessage.getChAddr()));
	            }
            }
            else {
            	log.warn("Received unhandled message type: " + dhcpMessage.getMessageType());
            }
        }
        else {
            // Note: in theory, we can't get here, because the
            // codec would have thrown an exception beforehand
            log.error("Received unknown message object: " + message.getClass());
        }
    }
	 
	/* (non-Javadoc)
	 * @see org.jboss.netty.channel.SimpleChannelUpstreamHandler#exceptionCaught(org.jboss.netty.channel.ChannelHandlerContext, org.jboss.netty.channel.ExceptionEvent)
	 */
	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception
	{
    	log.error("Exception caught: ", e.getCause());
    	e.getChannel().close();
	}
    
    /**
     * The main method.
     * 
     * @param args the arguments
     */
    public static void main(String[] args) {
        try {
			new ClientSimulatorV4(args);
		} catch (Exception e) {
			e.printStackTrace();
		}
    }

}
