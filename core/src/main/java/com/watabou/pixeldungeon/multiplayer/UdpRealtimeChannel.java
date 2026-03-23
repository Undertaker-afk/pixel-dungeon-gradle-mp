package com.watabou.pixeldungeon.multiplayer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

import org.json.JSONObject;

import com.watabou.pixeldungeon.PixelDungeon;
import com.watabou.pixeldungeon.utils.GLog;

public class UdpRealtimeChannel implements RealtimeChannel {
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
	public void addPeer( String peerId ) {
		GLog.i( "[Co-op] peer announced: %s (transport wiring pending)", peerId );
	}

	@Override
	public void send( CoopEvent event ) {
		if (socket == null || event == null) {
			return;
		}
		try {
			JSONObject json = new JSONObject();
			json.put( "kind", event.kind.name() );
			json.put( "actor", event.actorId );
			json.put( "depth", event.depth );
			json.put( "from", event.fromCell );
			json.put( "to", event.toCell );
			// Intentionally no direct UDP send path here: peer discovery does not carry
			// host/IP metadata anymore to avoid leaking endpoint information.
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
