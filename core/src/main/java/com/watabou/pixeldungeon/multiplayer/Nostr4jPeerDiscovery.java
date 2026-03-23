package com.watabou.pixeldungeon.multiplayer;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
	private final Map<String, CoopLobby> lobbies = new HashMap<String, CoopLobby>();

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
	public void announce( CoopLobby lobby, String playerId ) {
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
			payload.put( "roomId", lobby == null ? roomId : lobby.roomId );
			payload.put( "role", "DUNGEON_MASTER" );
			payload.put( "playerCount", lobby == null ? 1 : lobby.playerCount );
			payload.put( "maxPlayers", lobby == null ? 6 : lobby.maxPlayers );
			payload.put( "acceptingPlayers", lobby == null ? true : lobby.acceptingPlayers );
			payload.put( "unlockedClasses", lobby == null ? "WARRIOR" : lobby.unlockedClassesCsv );
			payload.put( "state", "looking-for-party" );
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

	@Override
	public List<CoopLobby> knownLobbies() {
		List<CoopLobby> result = new ArrayList<CoopLobby>( lobbies.values() );
		for (int i = result.size() - 1; i >= 0; i--) {
			CoopLobby lobby = result.get( i );
			if (System.currentTimeMillis() - lobby.announcedAtMillis > 90000) {
				result.remove( i );
			}
		}
		return result;
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
				String discoveredPeerId = payload.optString( "peerId", pubkey );
				String discoveredRoomId = payload.optString( "roomId", roomId );
				CoopLobby lobby = new CoopLobby(
					discoveredRoomId,
					discoveredPeerId,
					Math.max( 1, payload.optInt( "playerCount", 1 ) ),
					Math.max( 1, Math.min( 6, payload.optInt( "maxPlayers", 6 ) ) ),
					payload.optBoolean( "acceptingPlayers", true ),
					System.currentTimeMillis(),
					payload.optString( "unlockedClasses", "WARRIOR" ) );
				lobbies.put( discoveredRoomId + ":" + discoveredPeerId, lobby );
				listener.onPeer( discoveredPeerId );
			} catch (Exception ignored) {
		}
	}
}
