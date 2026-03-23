package com.watabou.pixeldungeon.multiplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class NostrRelayRealtimeChannelTest {

	@Test
	public void moveRoundtripSerializationAndDeliveryOverFallbackRelay() throws Exception {
		NostrRelayRealtimeChannel.InMemoryRelayNetwork relay = new NostrRelayRealtimeChannel.InMemoryRelayNetwork();
		NostrRelayRealtimeChannel sender = new NostrRelayRealtimeChannel( "memory://relay", relay );
		NostrRelayRealtimeChannel receiver = new NostrRelayRealtimeChannel( "memory://relay", relay );

		final CountDownLatch latch = new CountDownLatch( 1 );
		final AtomicReference<CoopEvent> received = new AtomicReference<CoopEvent>();

		sender.connect( "room-alpha", "peer-a", new RealtimeChannel.Listener() {
			@Override
			public void onEvent( CoopEvent event ) {
			}
		} );
		receiver.connect( "room-alpha", "peer-b", new RealtimeChannel.Listener() {
			@Override
			public void onEvent( CoopEvent event ) {
				received.set( event );
				latch.countDown();
			}
		} );

		sender.addPeer( new PeerEndpoint( "peer-b", null, 0, "peer-b", "memory://relay" ) );
		receiver.addPeer( new PeerEndpoint( "peer-a", null, 0, "peer-a", "memory://relay" ) );

		CoopEvent move = CoopEvent.move( "peer-a", 5, 11, 17 );
		sender.send( move );

		boolean delivered = latch.await( 2, TimeUnit.SECONDS );
		assertEquals( true, delivered );
		assertNotNull( received.get() );
		assertEquals( CoopEvent.Kind.MOVE, received.get().kind );
		assertEquals( "peer-a", received.get().actorId );
		assertEquals( 5, received.get().depth );
		assertEquals( 11, received.get().fromCell );
		assertEquals( 17, received.get().toCell );

		sender.disconnect();
		receiver.disconnect();
	}
}
