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
import com.watabou.pixeldungeon.utils.GLog;

public class NostrRelayRealtimeChannel implements RealtimeChannel {

	public static final String DEFAULT_RELAY_URL = "wss://nos.lol";
	private static final int REALTIME_KIND = 20001;

	interface RelayMessageListener {
		void onMessage( String message );
	}

	interface RelayClient {
		void connect();
		void send( String message );
		void close();
		boolean isOpen();
		void setListener( RelayMessageListener listener );
	}

	interface RelayClientFactory {
		RelayClient create( String relayUrl );
	}

	static class WebSocketRelayClient implements RelayClient {
		private final String relayUrl;
		private RelayMessageListener listener;
		private WebSocketClient ws;

		WebSocketRelayClient( String relayUrl ) {
			this.relayUrl = relayUrl;
		}

		@Override
		public void connect() {
			try {
				ws = new WebSocketClient( URI.create( relayUrl ) ) {
					@Override
					public void onOpen( ServerHandshake handshakedata ) {
					}

					@Override
					public void onMessage( String message ) {
						if (listener != null) {
							listener.onMessage( message );
						}
					}

					@Override
					public void onClose( int code, String reason, boolean remote ) {
					}

					@Override
					public void onError( Exception ex ) {
						PixelDungeon.reportException( ex );
					}
				};
				ws.connect();
			} catch (Exception e) {
				PixelDungeon.reportException( e );
			}
		}

		@Override
		public void send( String message ) {
			if (ws != null && ws.isOpen()) {
				ws.send( message );
			}
		}

		@Override
		public void close() {
			if (ws != null) {
				ws.close();
			}
			ws = null;
		}

		@Override
		public boolean isOpen() {
			return ws != null && ws.isOpen();
		}

		@Override
		public void setListener( RelayMessageListener listener ) {
			this.listener = listener;
		}
	}

	public static class InMemoryRelayNetwork implements RelayClientFactory {
		private final List<InMemoryRelayClient> clients = new ArrayList<InMemoryRelayClient>();

		@Override
		public RelayClient create( String relayUrl ) {
			InMemoryRelayClient client = new InMemoryRelayClient( this );
			synchronized (clients) {
				clients.add( client );
			}
			return client;
		}

		private void publish( String message ) {
			synchronized (clients) {
				for (InMemoryRelayClient client : clients) {
					client.deliver( message );
				}
			}
		}
	}

	private static class InMemoryRelayClient implements RelayClient {
		private final InMemoryRelayNetwork network;
		private RelayMessageListener listener;
		private boolean open;

		InMemoryRelayClient( InMemoryRelayNetwork network ) {
			this.network = network;
		}

		@Override
		public void connect() {
			open = true;
		}

		@Override
		public void send( String message ) {
			if (!open) {
				return;
			}
			network.publish( message );
		}

		@Override
		public void close() {
			open = false;
		}

		@Override
		public boolean isOpen() {
			return open;
		}

		@Override
		public void setListener( RelayMessageListener listener ) {
			this.listener = listener;
		}

		private void deliver( String message ) {
			if (open && listener != null) {
				listener.onMessage( message );
			}
		}
	}

	private final String relayUrl;
	private final RelayClientFactory relayFactory;
	private final Map<String, PeerEndpoint> peers = new HashMap<String, PeerEndpoint>();

	private RelayClient relayClient;
	private Listener listener;
	private String roomId;
	private String playerId;

	public NostrRelayRealtimeChannel() {
		this( DEFAULT_RELAY_URL, new RelayClientFactory() {
			@Override
			public RelayClient create( String relayUrl ) {
				return new WebSocketRelayClient( relayUrl );
			}
		} );
	}

	NostrRelayRealtimeChannel( String relayUrl, RelayClientFactory relayFactory ) {
		this.relayUrl = relayUrl;
		this.relayFactory = relayFactory;
	}

	@Override
	public void connect( String roomId, String playerId, Listener listener ) {
		this.roomId = roomId;
		this.playerId = playerId;
		this.listener = listener;
		relayClient = relayFactory.create( relayUrl );
		relayClient.setListener( new RelayMessageListener() {
			@Override
			public void onMessage( String message ) {
				handleMessage( message );
			}
		} );
		relayClient.connect();
		subscribeRoom();
	}

	@Override
	public void addPeer( PeerEndpoint peerEndpoint ) {
		if (peerEndpoint == null || peerEndpoint.peerId == null) {
			return;
		}
		peers.put( peerEndpoint.peerId, peerEndpoint );
	}

	@Override
	public PeerEndpoint localEndpoint() {
		if (playerId == null) {
			return null;
		}
		return new PeerEndpoint( playerId, null, 0, playerId, relayUrl );
	}

	@Override
	public void send( CoopEvent event ) {
		if (relayClient == null || !relayClient.isOpen() || event == null) {
			return;
		}
		String payload = CoopEventCodec.toJson( event );
		for (PeerEndpoint peer : peers.values()) {
			String frame = buildEventFrame( peer.peerId, payload );
			relayClient.send( frame );
			GLog.i( "[Co-op][Relay] sent %d bytes to %s", Integer.valueOf( payload.length() ), peer.peerId );
		}
	}

	@Override
	public void disconnect() {
		if (relayClient != null) {
			relayClient.close();
		}
		relayClient = null;
		listener = null;
		peers.clear();
	}

	private void subscribeRoom() {
		if (relayClient == null) {
			return;
		}
		JSONArray req = new JSONArray();
		req.put( "REQ" );
		req.put( "pd-coop-rt-" + roomId + "-" + playerId );
		JSONObject filter = new JSONObject();
		filter.put( "kinds", new JSONArray().put( REALTIME_KIND ) );
		filter.put( "#t", new JSONArray().put( "pd-coop-rt:" + roomId ) );
		req.put( filter );
		relayClient.send( req.toString() );
	}

	private String buildEventFrame( String destinationPeerId, String content ) {
		JSONObject event = new JSONObject();
		event.put( "id", UUID.randomUUID().toString().replace( "-", "" ) );
		event.put( "kind", REALTIME_KIND );
		event.put( "pubkey", playerId );
		event.put( "created_at", System.currentTimeMillis() / 1000 );
		event.put( "sig", "unsigned" );
		event.put( "content", content );

		JSONArray tags = new JSONArray();
		tags.put( new JSONArray().put( "t" ).put( "pd-coop-rt:" + roomId ) );
		tags.put( new JSONArray().put( "p" ).put( destinationPeerId ) );
		event.put( "tags", tags );

		JSONArray frame = new JSONArray();
		frame.put( "EVENT" );
		frame.put( event );
		return frame.toString();
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
			String fromPeer = event.optString( "pubkey", "" );
			if (playerId != null && playerId.equals( fromPeer )) {
				return;
			}
			if (!isForCurrentPlayer( event.optJSONArray( "tags" ) )) {
				return;
			}
			CoopEvent coopEvent = CoopEventCodec.fromJson( event.optString( "content", "{}" ) );
			long receiveTs = System.currentTimeMillis();
			GLog.i( "[Co-op][Relay] recv from %s at %d", fromPeer, Long.valueOf( receiveTs ) );
			listener.onEvent( coopEvent );
		} catch (Exception e) {
			GLog.w( "[Co-op][Relay] decode failure: %s", e.getMessage() );
			PixelDungeon.reportException( e );
		}
	}

	private boolean isForCurrentPlayer( JSONArray tags ) {
		if (tags == null || playerId == null) {
			return false;
		}
		for (int i = 0; i < tags.length(); i++) {
			JSONArray tag = tags.optJSONArray( i );
			if (tag == null || tag.length() < 2) {
				continue;
			}
			if ("p".equals( tag.optString( 0 ) ) && playerId.equals( tag.optString( 1 ) )) {
				return true;
			}
		}
		return false;
	}
}
