package com.watabou.pixeldungeon.multiplayer;

import java.net.URI;
import java.util.Enumeration;
import java.util.UUID;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import com.watabou.pixeldungeon.PixelDungeon;

/**
 * Lightweight Nostr relay discovery client for room announcements.
 * Uses kind 20000 custom events tagged with `t=pd-coop:<roomId>`.
 */
public class Nostr4jPeerDiscovery implements PeerDiscovery {

	private static final String RELAY_URL = "wss://nos.lol";
	private static final int DISCOVERY_KIND = 20000;

	private WebSocketClient socket;
	private Listener listener;
	private String roomId;
	private String playerId;

	@Override
	public void start( String roomId, String playerId, Listener listener ) {
		this.roomId = roomId;
		this.playerId = playerId;
		this.listener = listener;
		try {
			socket = new WebSocketClient( URI.create( RELAY_URL ) ) {
				@Override
				public void onOpen( ServerHandshake handshakedata ) {
					subscribeRoom();
				}

				@Override
				public void onMessage( String message ) {
					handleMessage( message );
				}

				@Override
				public void onClose( int code, String reason, boolean remote ) {
				}

				@Override
				public void onError( Exception ex ) {
					PixelDungeon.reportException( ex );
				}
			};
			socket.connect();
		} catch (Exception e) {
			PixelDungeon.reportException( e );
		}
	}

	@Override
	public void announce( String roomId, String playerId, int port ) {
		if (socket == null || !socket.isOpen()) {
			return;
		}
		try {
			JSONObject event = new JSONObject();
			event.put( "id", UUID.randomUUID().toString().replace( "-", "" ) );
			event.put( "kind", DISCOVERY_KIND );
			event.put( "pubkey", playerId );
			event.put( "created_at", System.currentTimeMillis() / 1000 );
			event.put( "sig", "unsigned" );

			JSONArray tags = new JSONArray();
			tags.put( new JSONArray().put( "t" ).put( "pd-coop:" + roomId ) );
			event.put( "tags", tags );

			JSONObject payload = new JSONObject();
			payload.put( "peerId", playerId );
			payload.put( "host", resolveHost() );
			payload.put( "port", port );
			event.put( "content", payload.toString() );

			JSONArray frame = new JSONArray();
			frame.put( "EVENT" );
			frame.put( event );
			socket.send( frame.toString() );
		} catch (Exception e) {
			PixelDungeon.reportException( e );
		}
	}

	@Override
	public void stop() {
		try {
			if (socket != null) {
				socket.close();
			}
		} catch (Exception e) {
			PixelDungeon.reportException( e );
		}
		socket = null;
		listener = null;
	}

	private void subscribeRoom() {
		if (socket == null || !socket.isOpen()) {
			return;
		}
		JSONArray req = new JSONArray();
		req.put( "REQ" );
		req.put( "pd-coop-" + roomId );
		JSONObject filter = new JSONObject();
		filter.put( "kinds", new JSONArray().put( DISCOVERY_KIND ) );
		filter.put( "#t", new JSONArray().put( "pd-coop:" + roomId ) );
		req.put( filter );
		socket.send( req.toString() );
	}

	private String resolveHost() {
		try {
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while (interfaces != null && interfaces.hasMoreElements()) {
				NetworkInterface net = interfaces.nextElement();
				Enumeration<InetAddress> addrs = net.getInetAddresses();
				while (addrs.hasMoreElements()) {
					InetAddress address = addrs.nextElement();
					if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
						return address.getHostAddress();
					}
				}
			}
		} catch (Exception ignored) {
		}
		return "127.0.0.1";
	}

	private void handleMessage( String message ) {
		if (listener == null || message == null) {
			return;
		}
		try {
			JSONArray frame = new JSONArray( message );
			if (frame.length() < 3 || !"EVENT".equals( frame.getString( 0 ) )) {
				return;
			}
			JSONObject event = frame.getJSONObject( 2 );
			String pubkey = event.optString( "pubkey", "" );
			if (playerId != null && playerId.equals( pubkey )) {
				return;
			}
			JSONObject payload = new JSONObject( event.optString( "content", "{}" ) );
			listener.onPeer(
				payload.optString( "peerId", pubkey ),
				payload.optString( "host", "" ),
				payload.optInt( "port", -1 ) );
		} catch (Exception ignored) {
		}
	}
}
