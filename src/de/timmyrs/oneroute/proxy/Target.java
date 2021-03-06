package de.timmyrs.oneroute.proxy;

import de.timmyrs.oneroute.enums.OneRoutePacket;
import de.timmyrs.oneroute.main.Main;
import de.timmyrs.oneroute.main.SomethingWentWrongException;
import de.timmyrs.oneroute.proxy.configuration.ListenerConfig;
import de.timmyrs.oneroute.utils.PacketReader;
import de.timmyrs.oneroute.utils.PacketWriter;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;

class Target extends Thread
{
	final Socket sock;
	private final ListenerConfig config;
	int lastData;
	private boolean authed = false;
	private boolean proxyConnection = false;

	Target(Socket sock, ListenerConfig config)
	{
		this.sock = sock;
		this.config = config;
		new Thread(this, "Target " + sock.getInetAddress().toString()).start();
	}

	public void run()
	{
		OutputStream out;
		int forPort;
		try
		{
			BufferedInputStream in = new BufferedInputStream(this.sock.getInputStream());
			out = this.sock.getOutputStream();
			do
			{
				if(in.available() > 2)
				{
					lastData = (int) (System.currentTimeMillis() / 1000L);
					PacketReader reader = new PacketReader(in);
					byte packetId = reader.readByte();
					boolean done;
					if(this.authed)
					{
						if(packetId == OneRoutePacket.PORTS_TO_OPEN.id)
						{
							ArrayList<Integer> unacceptedPorts = new ArrayList<>();
							int i;
							synchronized(Main.portListeners)
							{
								for(i = reader.readByte(); i > 0; i--)
								{
									int port = reader.readUnsignedShort();
									try
									{
										PortListener listener = new PortListener(port, this);
										Main.portListeners.add(listener);
										System.out.println("[" + this.sock.getInetAddress().toString() + "] Opened local port " + port + ".");
									}
									catch(IOException ignored)
									{
										unacceptedPorts.add(port);
									}
								}
							}
							PacketWriter writer = new PacketWriter(OneRoutePacket.UNACCEPTED_PORTS)
									.addByte((byte) unacceptedPorts
											.size());
							for(Integer port : unacceptedPorts)
							{
								writer.addUnsignedShort(port);
							}
							writer.send(out);
						}
						else if(packetId == OneRoutePacket.PORTS_TO_CLOSE.id)
						{
							final ArrayList<Integer> portsToClose = new ArrayList<>();
							for(int i = reader.readByte(); i > 0; i--)
							{
								portsToClose.add(reader.readUnsignedShort());
							}
							boolean disconnect = reader.readBoolean();
							synchronized(Main.portListeners)
							{
								do
								{
									done = true;
									for(PortListener listener : Main.portListeners)
									{
										if(portsToClose.contains(listener.port))
										{
											listener.interrupt();
											if(disconnect)
											{
												synchronized(listener.waitingClients)
												{
													for(Client c : listener.waitingClients)
													{
														c.end();
													}
												}
												synchronized(listener.clients)
												{
													for(Client c : listener.clients)
													{
														c.end();
													}
												}
											}
											System.out.println("[" + this.sock.getInetAddress().toString() + "] Closed local port " + listener.port);
											done = false;
											break;
										}
									}
								}
								while(!done);
							}
						}
					}
					else if(packetId == OneRoutePacket.AUTH.id)
					{
						if(reader.readByte() == 1)
						{
							if((this.config.pass.equals(reader.readString())) || (this.config.pass.equals("")))
							{
								this.authed = true;
								switch(reader.readByte())
								{
									default:
										throw new SomethingWentWrongException("Target wanted to open unknown connection type.");
									case 0x00:
										System.out.println("[" + this.sock.getInetAddress().toString() + "] Authentication succeeded.");
										new PacketWriter(OneRoutePacket.AUTH_RESPONSE)
												.addBoolean(true)
												.send(out);
										new TargetCloser(this);
										break;
									case 0x01:
										forPort = reader.readUnsignedShort();
										synchronized(Main.portListeners)
										{
											for(PortListener listener : Main.portListeners)
											{
												if(forPort == listener.port)
												{
													synchronized(listener.waitingClients)
													{
														if(listener.waitingClients.size() > 0)
														{
															new PacketWriter(OneRoutePacket.AUTH_RESPONSE)
																	.addBoolean(true)
																	.send(out);
															Client c = listener.waitingClients.get(0);
															reader.finish();
															proxyConnection = true;
															c.proxyTo(this.sock);
															synchronized(listener.clients)
															{
																listener.waitingClients.remove(c);
																listener.clients.add(c);
															}
															break;
														}
													}
												}
											}
										}
										if(!proxyConnection)
										{
											new PacketWriter(OneRoutePacket.AUTH_RESPONSE)
													.addBoolean(false)
													.addByte((byte) 0x00)
													.addString("Proxy Connection for port " + forPort + " was not needed.")
													.send(out);
											this.sock.close();
										}
										break;
								}
							}
							else
							{
								new PacketWriter(OneRoutePacket.AUTH_RESPONSE)
										.addBoolean(false)
										.addByte((byte) 0x03)
										.send(out);
								this.sock.close();
							}
						}
						else
						{
							new PacketWriter(OneRoutePacket.AUTH_RESPONSE)
									.addBoolean(false)
									.addByte((byte) 0x02)
									.send(out);
							this.sock.close();
						}
					}
					if(proxyConnection)
					{
						break;
					}
					reader.finish();
				}
			}
			while(!Thread.interrupted() && !this.sock.isClosed());
		}
		catch(SomethingWentWrongException e)
		{
			System.out.println(e.getMessage());
		}
		catch(Exception e)
		{
			e.printStackTrace();
		}
		synchronized(Main.portListeners)
		{
			for(PortListener listener : Main.portListeners)
			{
				if(listener.target == this)
				{
					synchronized(listener.waitingClients)
					{
						for(Client c : listener.waitingClients)
						{
							c.end();
						}
					}
					synchronized(listener.clients)
					{
						for(Client c : listener.clients)
						{
							c.end();
						}
					}
					listener.interrupt();
				}
			}
		}
		if(!proxyConnection)
		{
			System.out.println("[" + this.sock.getInetAddress().toString() + "] Connections & ports have been closed.");
		}
	}
}