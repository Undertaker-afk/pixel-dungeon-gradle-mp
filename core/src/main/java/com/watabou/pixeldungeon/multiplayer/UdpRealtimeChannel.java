package com.watabou.pixeldungeon.multiplayer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONObject;

import com.watabou.pixeldungeon.PixelDungeon;

public class UdpRealtimeChannel implements RealtimeChannel {

	private static class PeerAddress {
		final String host;
		final int port;

		PeerAddress( String host, int port ) {
			this.host = host;
			this.port = port;
		}
	}

	private final Map<String, PeerAddress> peers = new HashMap<String, PeerAddress>();
	private DatagramSocket socket;
	private Thread readThread;
	private Listener listener;

	@Override
	public void connect( String roomId, String playerId, Listener listener ) {
		this.listener = listener;
		try {
			socket = new DatagramSocket( 0 );
			startReader();
		} catch (Exception e) {
			PixelDungeon.reportException( e );
		}
	}

	@Override
	public int localPort() {
		return socket == null ? -1 : socket.getLocalPort();
	}

	@Override
	public void addPeer( String peerId, String host, int port ) {
		if (peerId == null || host == null || port <= 0) {
			return;
		}
		peers.put( peerId, new PeerAddress( host, port ) );
	}

	@Override
	public void send( CoopEvent event ) {
		if (socket == null || peers.isEmpty() || event == null) {
			return;
		}
		try {
			JSONObject json = new JSONObject();
			json.put( "kind", event.kind.name() );
			json.put( "actor", event.actorId );
			json.put( "depth", event.depth );
			json.put( "from", event.fromCell );
			json.put( "to", event.toCell );
			byte[] payload = json.toString().getBytes( "UTF-8" );

			for (PeerAddress peer : peers.values()) {
				DatagramPacket packet = new DatagramPacket(
					payload,
					payload.length,
					InetAddress.getByName( peer.host ),
					peer.port );
				socket.send( packet );
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
		peers.clear();
		listener = null;
	}

	private void startReader() {
		readThread = new Thread( new Runnable() {
			@Override
			public void run() {
				while (socket != null && !socket.isClosed()) {
					try {
						byte[] buf = new byte[2048];
						DatagramPacket packet = new DatagramPacket( buf, buf.length );
						socket.receive( packet );
						String text = new String( packet.getData(), 0, packet.getLength(), "UTF-8" );
						JSONObject json = new JSONObject( text );
						CoopEvent event = new CoopEvent(
							CoopEvent.Kind.valueOf( json.getString( "kind" ) ),
							json.getString( "actor" ),
							json.getInt( "depth" ),
							json.getInt( "from" ),
							json.getInt( "to" ),
							System.currentTimeMillis() );
						if (listener != null) {
							listener.onEvent( event );
						}
					} catch (Exception e) {
						if (socket != null && !socket.isClosed()) {
							PixelDungeon.reportException( e );
						}
					}
				}
			}
		}, "coop-udp-reader" );
		readThread.setDaemon( true );
		readThread.start();
	}
}
