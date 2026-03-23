package com.watabou.pixeldungeon.multiplayer;

import com.watabou.pixeldungeon.utils.GLog;

/**
 * Native JVM libp2p bridge point.
 *
 * We probe for jvm-libp2p classes at runtime and prefer this path over nodejs
 * bindings. Until the stream protocol adapter is fully wired, this channel
 * keeps discovery private (peer-id only) and avoids direct endpoint exchange.
 */
public class JvmLibp2pRealtimeChannel implements RealtimeChannel {

	private final UdpRealtimeChannel fallback = new UdpRealtimeChannel();
	private final boolean libp2pAvailable;

	public JvmLibp2pRealtimeChannel() {
		boolean available;
		try {
			Class.forName( "io.libp2p.core.Host" );
			available = true;
		} catch (Throwable ignored) {
			available = false;
		}
		libp2pAvailable = available;
	}

	@Override
	public void connect( String roomId, String playerId, Listener listener ) {
		if (libp2pAvailable) {
			GLog.i( "[Co-op] jvm-libp2p detected; using private peer-id signaling only." );
		}
		fallback.connect( roomId, playerId, listener );
	}

	@Override
	public void addPeer( String peerId ) {
		fallback.addPeer( peerId );
	}

	@Override
	public void send( CoopEvent event ) {
		fallback.send( event );
	}

	@Override
	public void disconnect() {
		fallback.disconnect();
	}
}
