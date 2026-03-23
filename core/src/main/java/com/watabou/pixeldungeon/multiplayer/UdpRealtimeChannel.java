package com.watabou.pixeldungeon.multiplayer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.watabou.pixeldungeon.PixelDungeon;
import com.watabou.pixeldungeon.utils.GLog;

public class UdpRealtimeChannel implements RealtimeChannel {
	private DatagramSocket socket;
	private Thread readThread;
	private Listener listener;
	private final Map<String, PeerEndpoint> peers = new ConcurrentHashMap<String, PeerEndpoint>();
	private PeerEndpoint localEndpoint;

	@Override
	public void connect( String roomId, String playerId, Listener listener ) {
		this.listener = listener;
		try {
			socket = new DatagramSocket( 0 );
			localEndpoint = new PeerEndpoint( playerId, resolveAdvertisedHost(), socket.getLocalPort(), null, null );
			startReader();
		} catch (Exception e) {
			PixelDungeon.reportException( e );
		}
	}

	@Override
	public void addPeer( PeerEndpoint peerEndpoint ) {
		if (peerEndpoint == null || peerEndpoint.peerId == null) {
			return;
		}
		peers.put( peerEndpoint.peerId, peerEndpoint );
		if (peerEndpoint.hasUdpEndpoint()) {
			GLog.i( "[Co-op][UDP] peer %s -> %s:%d", peerEndpoint.peerId, peerEndpoint.udpHost, Integer.valueOf( peerEndpoint.udpPort ) );
		} else {
			GLog.i( "[Co-op][UDP] peer %s has no routable UDP endpoint", peerEndpoint.peerId );
		}
	}

	@Override
	public PeerEndpoint localEndpoint() {
		return localEndpoint;
	}

	@Override
	public void send( CoopEvent event ) {
		if (socket == null || event == null || peers.isEmpty()) {
			return;
		}
		try {
			byte[] bytes = CoopEventCodec.toUtf8( event );
			for (PeerEndpoint peer : peers.values()) {
				if (!peer.hasUdpEndpoint()) {
					continue;
				}
				DatagramPacket packet = new DatagramPacket( bytes, bytes.length, InetAddress.getByName( peer.udpHost ), peer.udpPort );
				socket.send( packet );
				GLog.i( "[Co-op][UDP] sent %d bytes to %s (%s:%d)", Integer.valueOf( bytes.length ), peer.peerId, peer.udpHost, Integer.valueOf( peer.udpPort ) );
			}
		} catch (Exception e) {
			PixelDungeon.reportException( e );
		}
	}

	@Override
	public void disconnect() {
		if (socket != null) {
			socket.close();
			socket = null;
		}
		listener = null;
		localEndpoint = null;
		peers.clear();
	}

	private void startReader() {
		readThread = new Thread( new Runnable() {
			@Override
			public void run() {
				while (socket != null && !socket.isClosed()) {
					try {
						byte[] buf = new byte[4096];
						DatagramPacket packet = new DatagramPacket( buf, buf.length );
						socket.receive( packet );
						CoopEvent event = CoopEventCodec.fromUtf8( packet.getData(), packet.getLength() );
						long receiveTs = System.currentTimeMillis();
						GLog.i( "[Co-op][UDP] recv %d bytes from %s at %d", Integer.valueOf( packet.getLength() ), packet.getSocketAddress(), Long.valueOf( receiveTs ) );
						if (listener != null) {
							listener.onEvent( event );
						}
					} catch (Exception e) {
						if (socket != null && !socket.isClosed()) {
							GLog.w( "[Co-op][UDP] decode/receive failure: %s", e.getMessage() );
							PixelDungeon.reportException( e );
						}
					}
				}
			}
		}, "coop-udp-reader" );
		readThread.setDaemon( true );
		readThread.start();
	}

	private String resolveAdvertisedHost() {
		try {
			String local = InetAddress.getLocalHost().getHostAddress();
			if (local != null && local.length() > 0 && !"127.0.0.1".equals( local )) {
				return local;
			}
		} catch (Exception ignored) {
		}
		return null;
	}
}
