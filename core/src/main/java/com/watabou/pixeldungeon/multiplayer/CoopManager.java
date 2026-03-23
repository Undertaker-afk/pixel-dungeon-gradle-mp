package com.watabou.pixeldungeon.multiplayer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

import org.json.JSONArray;
import org.json.JSONObject;

import com.watabou.pixeldungeon.Dungeon;
import com.watabou.pixeldungeon.PixelDungeon;
import com.watabou.pixeldungeon.actors.Actor;
import com.watabou.pixeldungeon.actors.Char;
import com.watabou.pixeldungeon.actors.blobs.Blob;
import com.watabou.pixeldungeon.actors.hero.HeroClass;
import com.watabou.pixeldungeon.actors.mobs.Mob;
import com.watabou.pixeldungeon.coop.gameplay.RemoteHero;
import com.watabou.pixeldungeon.Badges;
import com.watabou.pixeldungeon.items.Heap;
import com.watabou.pixeldungeon.items.Item;
import com.watabou.pixeldungeon.levels.Level;
import com.watabou.pixeldungeon.levels.Room;
import com.watabou.pixeldungeon.levels.Terrain;
import com.watabou.pixeldungeon.scenes.GameScene;
import com.watabou.pixeldungeon.sprites.CharSprite;
import com.watabou.pixeldungeon.utils.GLog;
import com.watabou.utils.Random;

public class CoopManager {

	private static final CoopManager INSTANCE = new CoopManager();
	private static final long LEVEL_SYNC_WAIT_MS = 5000L;
	private static final long INTENT_MIN_INTERVAL_MS = 80L;
	private static final int MAX_REJECT_LOG_ENTRIES = 64;
	private static final long WORLD_DIFF_INTERVAL_MS = 200L;
	private static final int FULL_SYNC_INTERVAL_TURNS = 12;
	private static final int HASH_INTERVAL_TURNS = 6;

	public static CoopManager instance() {
		return INSTANCE;
	}

	private final PeerDiscovery peerDiscovery = new Nostr4jPeerDiscovery();
	private final RealtimeChannel realtimeChannel = new JvmLibp2pRealtimeChannel();

	private boolean connected;
	private String roomId;
	private String playerId;
	private final Set<String> peerIds = new HashSet<String>();
	private final Set<String> bannedPeerIds = new HashSet<String>();
	private final Map<String, String> sessionTokenByPeerId = new HashMap<String, String>();
	private final Set<String> deadPeerIds = new HashSet<String>();
	private final Object levelSyncLock = new Object();
	private final Map<Integer, Long> levelSeedByDepth = new HashMap<Integer, Long>();
	private final Map<Integer, String> levelHashByDepth = new HashMap<Integer, String>();
	private final Map<String, RemoteHero> remoteHeroesByPeerId = new HashMap<String, RemoteHero>();
	private final Map<String, Deque<CoopEvent>> intentQueueByPeerId = new HashMap<String, Deque<CoopEvent>>();
	private final Map<String, Long> lastAcceptedIntentMsByPeerId = new HashMap<String, Long>();
	private final Map<String, Set<String>> inventoryByPeerId = new HashMap<String, Set<String>>();
	private final Deque<String> rejectedIntentLog = new LinkedList<String>();
	private final Map<Integer, String> stableMobIdByActorId = new HashMap<Integer, String>();
	private final Map<String, Integer> localMobActorIdByStableId = new HashMap<String, Integer>();
	private final Map<String, Integer> remoteHeapCellByStableId = new HashMap<String, Integer>();
	private String authoritativePeerId;
	private String joinKey;
	private String joinStatus = "Idle";
	private long sessionSeedBase;
	private long lastWorldDiffSentAtMs;
	private int turnsSinceFullStateSync;
	private int turnsSinceHashBroadcast;

	private CoopManager() {
	}

	public void ensureConnected( String configuredRoomId ) {
		if (connected) {
			return;
		}

		roomId = configuredRoomId;
		playerId = PixelDungeon.coopPlayerUuid();
		authoritativePeerId = isHost() ? playerId : null;
		joinKey = isHost() ? randomToken() : "";
		joinStatus = isHost() ? "Hosting - waiting for players" : "Connecting";
		sessionSeedBase = System.currentTimeMillis() ^ (((long)roomId.hashCode()) << 21);
		if (isHost()) {
			sessionTokenByPeerId.put( playerId, randomToken() );
			PixelDungeon.clearCoopSessionResume();
		}
		if (isHost()) {
			PixelDungeon.coopSimulationPolicy( CoopSimulationPolicy.HOST_AUTHORITATIVE );
			GLog.i( "[Co-op] DM host '%s' running authoritative simulation.", playerId );
		}

		realtimeChannel.connect( roomId, playerId, new RealtimeChannel.Listener() {
			@Override
			public void onEvent( CoopEvent event ) {
				handleIncoming( event );
			}
		} );

		peerDiscovery.start( roomId, playerId, new PeerDiscovery.Listener() {
			@Override
			public void onPeer( PeerEndpoint peerEndpoint ) {
				String peerId = peerEndpoint == null ? null : peerEndpoint.peerId;
				if (peerId == null || peerId.length() == 0 || playerId.equals( peerId ) || bannedPeerIds.contains( peerId )) {
					return;
				}
				realtimeChannel.addPeer( peerEndpoint );
				if (peerIds.add( peerId )) {
					GLog.i( "[Co-op] peer connected: %s", peerId );
					if (isHost()) {
						ensureRemoteHero( peerId );
						resendLevelSyncToLateJoiner( peerId );
					} else {
						sendJoinRequest( peerEndpoint == null ? null : peerEndpoint.peerId );
					}
				}
			}
		} );
		peerDiscovery.announce( buildLobbyAnnouncement(), playerId );

		connected = true;
		GLog.p( "[Co-op] Session ready in room '%s' (%s)", roomId, PixelDungeon.coopRole().name() );
	}

	public void disconnect() {
		if (!connected) {
			return;
		}
		publishDespawn();
		peerDiscovery.stop();
		realtimeChannel.disconnect();
		clearRemoteHeroes();
		peerIds.clear();
		bannedPeerIds.clear();
		sessionTokenByPeerId.clear();
		deadPeerIds.clear();
		intentQueueByPeerId.clear();
		lastAcceptedIntentMsByPeerId.clear();
		inventoryByPeerId.clear();
		rejectedIntentLog.clear();
		stableMobIdByActorId.clear();
		localMobActorIdByStableId.clear();
		remoteHeapCellByStableId.clear();
		authoritativePeerId = null;
		joinKey = "";
		joinStatus = "Offline";
		lastWorldDiffSentAtMs = 0L;
		turnsSinceFullStateSync = 0;
		turnsSinceHashBroadcast = 0;
		synchronized (levelSyncLock) {
			levelSeedByDepth.clear();
			levelHashByDepth.clear();
		}
		connected = false;
	}

	public List<CoopLobby> knownLobbies() {
		return peerDiscovery.knownLobbies();
	}

	public String reconnectStatus() {
		if (connected) {
			return joinStatus;
		}
		if (PixelDungeon.canRejoinCoopRoom( PixelDungeon.coopRoom() )) {
			return "Saved resume token for room " + PixelDungeon.coopSessionRoom();
		}
		return "No resumable session token";
	}

	public boolean canAttemptRejoin() {
		return PixelDungeon.canRejoinCoopRoom( PixelDungeon.coopRoom() );
	}

	public void clearRejoinState() {
		PixelDungeon.clearCoopSessionResume();
		joinStatus = "Resume token cleared";
	}

	public boolean rejoinLastSession() {
		if (!PixelDungeon.canRejoinCoopRoom( null )) {
			joinStatus = "No resumable session token";
			return false;
		}
		PixelDungeon.coopEnabled( true );
		PixelDungeon.coopRoom( PixelDungeon.coopSessionRoom() );
		joinStatus = "Rejoin armed for room " + PixelDungeon.coopSessionRoom();
		return true;
	}

	public void kickAllPlayers() {
		bannedPeerIds.addAll( peerIds );
		for (String peerId : peerIds) {
			sessionTokenByPeerId.remove( peerId );
		}
		peerIds.clear();
		GLog.w( "[Co-op] All connected players were kicked from this local session." );
	}

	public void clearBans() {
		bannedPeerIds.clear();
		deadPeerIds.clear();
		GLog.i( "[Co-op] Player bans cleared." );
	}

	private CoopLobby buildLobbyAnnouncement() {
		int maxPlayers = 6;
		int playerCount = Math.min( maxPlayers, 1 + peerIds.size() );
		boolean accepting = playerCount < maxPlayers;
		return new CoopLobby(
			roomId,
			playerId,
			playerCount,
			maxPlayers,
			accepting,
			System.currentTimeMillis(),
			enabledClassesCsv(),
			realtimeChannel.localEndpoint(),
			joinKey,
			true,
			Math.max( 0, Dungeon.depth ) );
	}

	private String enabledClassesCsv() {
		StringBuilder sb = new StringBuilder();
		for (HeroClass heroClass : HeroClass.values()) {
			if (heroClass == HeroClass.WARRIOR || Badges.isUnlocked( heroClass.masteryBadge() )) {
				if (sb.length() > 0) {
					sb.append( "," );
				}
				sb.append( heroClass.name() );
			}
		}
		return sb.toString();
	}

	public void publishMove( int fromCell, int toCell ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.move( playerId, Dungeon.depth, fromCell, toCell ) );
		if (isHost()) {
			broadcastTurnOutcome( playerId, CoopEvent.Kind.MOVE, fromCell, toCell, true, null );
			onResolvedAuthoritativeAction();
		}
	}

	public void publishAttack( int fromCell, int toCell ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.attack( playerId, Dungeon.depth, fromCell, toCell ) );
		if (isHost()) {
			broadcastTurnOutcome( playerId, CoopEvent.Kind.ATTACK, fromCell, toCell, true, null );
			onResolvedAuthoritativeAction();
		}
	}

	public void publishItemPickup( String itemName, int cell ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.itemPickup( playerId, Dungeon.depth, itemName, cell ) );
	}

	public void publishItemDrop( String itemName, int cell ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.itemDrop( playerId, Dungeon.depth, itemName, cell ) );
	}

	public void publishItemUse( String itemName ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.itemUse( playerId, Dungeon.depth, itemName ) );
	}

	public void publishDoorUnlock( int cell ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.doorUnlock( playerId, Dungeon.depth, cell ) );
	}

	public void publishDescend( int cell ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.descend( playerId, Dungeon.depth, cell ) );
	}

	public void publishAscend( int cell ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.ascend( playerId, Dungeon.depth, cell ) );
	}

	public void publishDeath( String cause ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		if (!isHost()) {
			GLog.w( "[Co-op] Client attempted direct state mutation DEATH; blocked." );
			return;
		}
		realtimeChannel.send( CoopEvent.death( playerId, Dungeon.depth, cause ) );
	}

	public void publishBuff( String buffName, String operation ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		if (!isHost()) {
			GLog.w( "[Co-op] Client attempted direct state mutation BUFF; blocked." );
			return;
		}
		realtimeChannel.send( CoopEvent.buff( playerId, Dungeon.depth, buffName, operation ) );
	}

	public void publishDespawn() {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		if (!isHost()) {
			GLog.w( "[Co-op] Client attempted direct state mutation DESPAWN; blocked." );
			return;
		}
		realtimeChannel.send( CoopEvent.despawn( playerId, Dungeon.depth ) );
		GLog.i( "[Co-op][RemoteHero] local despawn published actor=%s depth=%d", playerId, Integer.valueOf( Dungeon.depth ) );
	}

	public void prepareLevelSeed( int depth ) {
		if (!connected || !PixelDungeon.coopEnabled() || depth <= 0) {
			return;
		}
		Long seed = isHost() ? ensureHostLevelSeed( depth ) : waitForLevelSeed( depth );
		if (seed == null) {
			GLog.w( "[Co-op] Missing LEVEL_SYNC for depth %d; falling back to local RNG.", Integer.valueOf( depth ) );
			return;
		}
		Random.setSeed( seed.longValue() );
		GLog.i( "[Co-op] Applied canonical level seed depth=%d seed=%d", Integer.valueOf( depth ), seed );
	}

	public void onLevelCreated( int depth, Level level ) {
		if (!connected || !PixelDungeon.coopEnabled() || depth <= 0 || level == null) {
			return;
		}
		String hash = computeFloorChecksum();
		synchronized (levelSyncLock) {
			levelHashByDepth.put( Integer.valueOf( depth ), hash );
		}
		GLog.i( "[Co-op] Level hash depth=%d hash=%s", Integer.valueOf( depth ), hash );
		realtimeChannel.send( CoopEvent.levelHash( playerId, depth, hash ) );
	}

	private void handleIncoming( CoopEvent event ) {
		if (!connected || event == null || playerId == null) {
			return;
		}
		if (playerId.equals( event.actorId )) {
			return;
		}
		if (event.kind == CoopEvent.Kind.LEVEL_SYNC) {
			handleLevelSync( event );
			return;
		}
		if (event.kind == CoopEvent.Kind.LEVEL_HASH) {
			handleLevelHash( event );
			return;
		}
		if (event.kind == CoopEvent.Kind.TURN_OUTCOME) {
			if (!isEventFromAuthoritativeHost( event )) {
				rejectStateMutation( event, "TURN_OUTCOME is only accepted from authoritative host" );
				return;
			}
			applyTurnOutcome( event );
			return;
		}
		if (event.kind == CoopEvent.Kind.FULL_STATE_SYNC) {
			if (!isEventFromAuthoritativeHost( event )) {
				rejectStateMutation( event, "FULL_STATE_SYNC is only accepted from authoritative host" );
				return;
			}
			applyAuthoritativeState( event );
			return;
		}
		if (event.kind == CoopEvent.Kind.WORLD_DIFF) {
			if (!isEventFromAuthoritativeHost( event )) {
				rejectStateMutation( event, "WORLD_DIFF is only accepted from authoritative host" );
				return;
			}
			applyWorldDiff( event );
			return;
		}
		if (event.kind == CoopEvent.Kind.SNAPSHOT_REQUEST) {
			if (!isHost()) {
				return;
			}
			sendFullStateSync( "snapshot-request:" + event.actorId + ":" + event.payload.optString( "reason", "unspecified" ), event.actorId, event.actorId );
			return;
		}
		if (event.kind == CoopEvent.Kind.JOIN_REQUEST) {
			if (isHost()) {
				handleJoinRequest( event );
			}
			return;
		}
		if (event.kind == CoopEvent.Kind.JOIN_RESULT) {
			handleJoinResult( event );
			return;
		}
		if (event.kind == CoopEvent.Kind.DESPAWN) {
			if (!isEventFromAuthoritativeHost( event )) {
				rejectStateMutation( event, "DESPAWN is only accepted from authoritative host" );
				return;
			}
			despawnRemoteHero( event.actorId, "remote-event" );
			return;
		}
		if (event.depth != Dungeon.depth) {
			return;
		}

		if (event.kind == CoopEvent.Kind.UNKNOWN) {
			GLog.w( "[Co-op] Ignoring unknown event kind from %s: %s", event.actorId, event.kindRaw );
			return;
		}

		if (!isIntentKind( event.kind ) && !isHost()) {
			return;
		}

		if (isIntentKind( event.kind )) {
			if (!isHost()) {
				return;
			}
			enqueueIntent( event );
			RemoteHero remoteHero = ensureRemoteHero( event.actorId );
			if (remoteHero != null) {
				consumeIntentForRemoteHero( remoteHero );
			}
			return;
		}

		if (isHost()) {
			rejectStateMutation( event, "non-intent event from client rejected" );
		}
	}

	public CoopSimulationPolicy simulationPolicy() {
		return PixelDungeon.coopRuntimeSettings().simulationPolicy();
	}

	public boolean isLocalInputReady() {
		if (Dungeon.hero == null) {
			return false;
		}
		if (!PixelDungeon.coopEnabled()) {
			return Dungeon.hero.ready;
		}
		if (simulationPolicy() == CoopSimulationPolicy.LOCKSTEP) {
			return Dungeon.hero.ready && !hasPendingRemoteIntents();
		}
		return Dungeon.hero.ready;
	}

	public void onSimulationTick() {
		if (!connected || !PixelDungeon.coopEnabled() || Dungeon.depth <= 0) {
			return;
		}
		if (!isHost()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastWorldDiffSentAtMs >= WORLD_DIFF_INTERVAL_MS) {
			sendWorldDiff();
			lastWorldDiffSentAtMs = now;
		}
	}

	public boolean resolveRemoteHeroTurn( RemoteHero remoteHero ) {
		if (remoteHero == null || !PixelDungeon.coopEnabled() || Dungeon.depth <= 0) {
			return false;
		}
		if (simulationPolicy() != CoopSimulationPolicy.HOST_AUTHORITATIVE && !isHost()) {
			return false;
		}
		return consumeIntentForRemoteHero( remoteHero );
	}

	private boolean hasPendingRemoteIntents() {
		for (Deque<CoopEvent> queue : intentQueueByPeerId.values()) {
			if (queue != null && !queue.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private void enqueueIntent( CoopEvent event ) {
		if (event == null || event.actorId == null) {
			return;
		}
		Deque<CoopEvent> queue = intentQueueByPeerId.get( event.actorId );
		if (queue == null) {
			queue = new LinkedList<CoopEvent>();
			intentQueueByPeerId.put( event.actorId, queue );
		}
		queue.addLast( event );
		List<CoopEvent> ordered = new ArrayList<CoopEvent>( queue );
		Collections.sort( ordered, new Comparator<CoopEvent>() {
			@Override
			public int compare( CoopEvent left, CoopEvent right ) {
				int byTimestamp = left.sentAtMs < right.sentAtMs ? -1 : (left.sentAtMs == right.sentAtMs ? 0 : 1);
				if (byTimestamp != 0) {
					return byTimestamp;
				}
				String leftPeer = left.actorId == null ? "" : left.actorId;
				String rightPeer = right.actorId == null ? "" : right.actorId;
				return leftPeer.compareTo( rightPeer );
			}
		} );
		queue.clear();
		queue.addAll( ordered );
	}

	private boolean consumeIntentForRemoteHero( RemoteHero remoteHero ) {
		Deque<CoopEvent> queue = intentQueueByPeerId.get( remoteHero.peerId );
		if (queue == null || queue.isEmpty()) {
			return false;
		}
		CoopEvent intent = queue.pollFirst();
		if (intent == null) {
			return false;
		}
		String rejectionReason = validateIntent( remoteHero, intent );
		if (rejectionReason != null) {
			rejectIntent( remoteHero.peerId, intent, rejectionReason );
			broadcastTurnOutcome( remoteHero.peerId, intent.kind, intent.fromCell, intent.toCell, false, rejectionReason );
			return false;
		}
		if (intent.kind == CoopEvent.Kind.MOVE) {
			applyRemoteMove( remoteHero, intent.fromCell, intent.toCell );
		} else if (intent.kind == CoopEvent.Kind.ATTACK) {
			applyRemoteAttack( remoteHero, intent.fromCell, intent.toCell );
		}
		if (isHost()) {
			applyInventoryMutation( remoteHero.peerId, intent );
			lastAcceptedIntentMsByPeerId.put( remoteHero.peerId, Long.valueOf( System.currentTimeMillis() ) );
			broadcastTurnOutcome( remoteHero.peerId, intent.kind, intent.fromCell, intent.toCell, true, null );
			onResolvedAuthoritativeAction();
		}
		return true;
	}

	private RemoteHero ensureRemoteHero( String peerId ) {
		if (peerId == null || peerId.length() == 0 || Dungeon.level == null) {
			return null;
		}
		RemoteHero remoteHero = remoteHeroesByPeerId.get( peerId );
		if (remoteHero != null) {
			return remoteHero;
		}
		int spawnCell = remoteSpawnCell();
		remoteHero = new RemoteHero( peerId, peerId, spawnCell );
		remoteHeroesByPeerId.put( peerId, remoteHero );
		GameScene.addRemoteHero( remoteHero );
		if (remoteHero.sprite != null) {
			remoteHero.sprite.showStatus( CharSprite.NEUTRAL, "SPAWN" );
		}
		GLog.i( "[Co-op][RemoteHero] spawn peer=%s cell=%d", peerId, Integer.valueOf( spawnCell ) );
		return remoteHero;
	}

	private int remoteSpawnCell() {
		if (Dungeon.level == null) {
			return 0;
		}
		if (isCellAvailable( Dungeon.level.entrance )) {
			return Dungeon.level.entrance;
		}
		if (Dungeon.hero != null && isCellAvailable( Dungeon.hero.pos )) {
			return Dungeon.hero.pos;
		}
		for (int i = 0; i < Level.LENGTH; i++) {
			if (isCellAvailable( i )) {
				return i;
			}
		}
		return Dungeon.level.entrance;
	}

	private void applyRemoteMove( RemoteHero remoteHero, int fromCell, int toCell ) {
		if (remoteHero == null) {
			return;
		}
		int safeFrom = remoteHero.pos;
		if (fromCell >= 0 && fromCell < Level.LENGTH) {
			safeFrom = fromCell;
		}
		if (!isValidMovement( remoteHero, safeFrom, toCell )) {
			GLog.w( "[Co-op][RemoteHero] rejected move peer=%s %d->%d", remoteHero.peerId, Integer.valueOf( safeFrom ), Integer.valueOf( toCell ) );
			return;
		}
		Actor.freeCell( remoteHero.pos );
		remoteHero.snapshotPosition( toCell );
		Actor.occupyCell( remoteHero );
		if (remoteHero.sprite != null) {
			remoteHero.sprite.move( safeFrom, toCell );
			remoteHero.sprite.showStatus( CharSprite.NEUTRAL, "MOVE" );
		}
		GLog.i( "[Co-op][RemoteHero] move peer=%s %d->%d", remoteHero.peerId, Integer.valueOf( safeFrom ), Integer.valueOf( toCell ) );
	}

	private void applyRemoteAttack( RemoteHero remoteHero, int fromCell, int toCell ) {
		if (remoteHero == null || toCell < 0 || toCell >= Level.LENGTH) {
			return;
		}
		if (fromCell >= 0 && fromCell < Level.LENGTH && remoteHero.pos != fromCell) {
			if (isCellAvailableForRemote( remoteHero, fromCell )) {
				Actor.freeCell( remoteHero.pos );
				remoteHero.snapshotPosition( fromCell );
				Actor.occupyCell( remoteHero );
				if (remoteHero.sprite != null) {
					remoteHero.sprite.place( fromCell );
				}
			}
		}
		if (remoteHero.sprite != null) {
			remoteHero.sprite.attack( toCell );
			remoteHero.sprite.showStatus( CharSprite.WARNING, "ATK" );
		}
		GLog.i( "[Co-op][RemoteHero] attack peer=%s from=%d to=%d", remoteHero.peerId, Integer.valueOf( remoteHero.pos ), Integer.valueOf( toCell ) );
	}

	private boolean isValidMovement( RemoteHero remoteHero, int fromCell, int toCell ) {
		if (toCell < 0 || toCell >= Level.LENGTH || fromCell < 0 || fromCell >= Level.LENGTH) {
			return false;
		}
		if (!Level.passable[toCell]) {
			return false;
		}
		if (!Level.adjacent( fromCell, toCell )) {
			return false;
		}
		return isCellAvailableForRemote( remoteHero, toCell );
	}

	private boolean isCellAvailable( int cell ) {
		if (cell < 0 || cell >= Level.LENGTH) {
			return false;
		}
		if (!Level.passable[cell]) {
			return false;
		}
		return Actor.findChar( cell ) == null;
	}

	private boolean isCellAvailableForRemote( RemoteHero remoteHero, int cell ) {
		if (cell < 0 || cell >= Level.LENGTH || !Level.passable[cell]) {
			return false;
		}
		Char occupant = Actor.findChar( cell );
		return occupant == null || occupant == remoteHero;
	}

	private boolean isCellAvailableForRemote( Mob mob, int cell ) {
		if (cell < 0 || cell >= Level.LENGTH || !Level.passable[cell]) {
			return false;
		}
		Char occupant = Actor.findChar( cell );
		return occupant == null || occupant == mob;
	}

	private void despawnRemoteHero( String peerId, String reason ) {
		RemoteHero remoteHero = remoteHeroesByPeerId.remove( peerId );
		if (remoteHero == null) {
			return;
		}
		intentQueueByPeerId.remove( peerId );
		inventoryByPeerId.remove( peerId );
		lastAcceptedIntentMsByPeerId.remove( peerId );
		GameScene.removeRemoteHero( remoteHero );
		GLog.i( "[Co-op][RemoteHero] despawn peer=%s reason=%s", peerId, reason );
	}

	private void clearRemoteHeroes() {
		for (String peerId : new ArrayList<String>( remoteHeroesByPeerId.keySet() )) {
			despawnRemoteHero( peerId, "disconnect" );
		}
		remoteHeroesByPeerId.clear();
	}

	private boolean isHost() {
		return PixelDungeon.coopRole() == CoopRole.DUNGEON_MASTER;
	}

	private Long ensureHostLevelSeed( int depth ) {
		Long seed;
		boolean created = false;
		synchronized (levelSyncLock) {
			seed = levelSeedByDepth.get( Integer.valueOf( depth ) );
			if (seed == null) {
				seed = Long.valueOf( nextSeedForDepth( depth ) );
				levelSeedByDepth.put( Integer.valueOf( depth ), seed );
				created = true;
			}
		}
		if (created) {
			GLog.i( "[Co-op] Host created canonical seed depth=%d seed=%d", Integer.valueOf( depth ), seed );
		}
		realtimeChannel.send( CoopEvent.levelSync( playerId, depth, seed.longValue() ) );
		return seed;
	}

	private Long waitForLevelSeed( int depth ) {
		synchronized (levelSyncLock) {
			Long seed = levelSeedByDepth.get( Integer.valueOf( depth ) );
			if (seed != null) {
				return seed;
			}
			long deadline = System.currentTimeMillis() + LEVEL_SYNC_WAIT_MS;
			while ((seed = levelSeedByDepth.get( Integer.valueOf( depth ) )) == null && System.currentTimeMillis() < deadline) {
				try {
					levelSyncLock.wait( 250L );
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			return seed;
		}
	}

	private long nextSeedForDepth( int depth ) {
		return sessionSeedBase ^ (depth * 0x9E3779B97F4A7C15L);
	}

	private void handleLevelSync( CoopEvent event ) {
		if (event.levelSeed == null || event.depth <= 0) {
			return;
		}
		synchronized (levelSyncLock) {
			levelSeedByDepth.put( Integer.valueOf( event.depth ), event.levelSeed );
			levelSyncLock.notifyAll();
		}
		if (!isHost()) {
			authoritativePeerId = event.actorId;
		}
		GLog.i( "[Co-op] Received LEVEL_SYNC depth=%d seed=%d from %s", Integer.valueOf( event.depth ), event.levelSeed, event.actorId );
	}

	private void handleLevelHash( CoopEvent event ) {
		if (event.levelHash == null || event.depth <= 0) {
			return;
		}
		String localHash;
		synchronized (levelSyncLock) {
			localHash = levelHashByDepth.get( Integer.valueOf( event.depth ) );
		}
		if (localHash == null) {
			GLog.i( "[Co-op] Received remote level hash depth=%d from %s before local hash was ready: %s",
				Integer.valueOf( event.depth ), event.actorId, event.levelHash );
			return;
		}
		if (localHash.equals( event.levelHash )) {
			GLog.p( "[Co-op] Determinism OK depth=%d (%s)", Integer.valueOf( event.depth ), localHash );
		} else {
			GLog.w( "[Co-op] Determinism mismatch depth=%d local=%s remote=%s from=%s",
				Integer.valueOf( event.depth ), localHash, event.levelHash, event.actorId );
			if (!isHost()) {
				requestSnapshotResync( "checksum-mismatch-depth-" + event.depth );
			}
		}
	}

	private void resendLevelSyncToLateJoiner( String peerId ) {
		if (!isHost() || peerId == null) {
			return;
		}
		Long seed;
		synchronized (levelSyncLock) {
			seed = levelSeedByDepth.get( Integer.valueOf( Dungeon.depth ) );
		}
		if (seed == null || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.levelSync( playerId, Dungeon.depth, seed.longValue() ) );
		GLog.i( "[Co-op] Resent LEVEL_SYNC to peer=%s depth=%d seed=%d", peerId, Integer.valueOf( Dungeon.depth ), seed );
	}

	private void sendJoinRequest( String hostPeerId ) {
		if (!connected || isHost()) {
			return;
		}
		String sessionToken = "";
		if (roomId != null && roomId.equals( PixelDungeon.coopSessionRoom() )) {
			sessionToken = PixelDungeon.coopSessionToken();
		}
		CoopLobby lobby = lobbyByHost( hostPeerId );
		String requestedJoinKey = lobby == null ? "" : lobby.joinKey;
		realtimeChannel.send( CoopEvent.joinRequest(
			playerId,
			Math.max( 1, Dungeon.depth <= 0 ? 1 : Dungeon.depth ),
			requestedJoinKey,
			sessionToken,
			Dungeon.depth,
			Dungeon.hero != null && Dungeon.hero.HP <= 0 ) );
		joinStatus = sessionToken.length() > 0 ? "Rejoin requested" : "Join requested";
	}

	private CoopLobby lobbyByHost( String hostPeerId ) {
		if (hostPeerId == null || hostPeerId.length() == 0) {
			return null;
		}
		for (CoopLobby lobby : peerDiscovery.knownLobbies()) {
			if (hostPeerId.equals( lobby.hostPeerId ) && (roomId == null || roomId.equals( lobby.roomId ))) {
				return lobby;
			}
		}
		return null;
	}

	private void handleJoinRequest( CoopEvent event ) {
		if (event == null || event.payload == null || event.actorId == null) {
			return;
		}
		String requester = event.actorId;
		if (bannedPeerIds.contains( requester )) {
			sendJoinResult( requester, false, "banned", "" );
			return;
		}
		if (deadPeerIds.contains( requester ) || event.payload.optBoolean( "deadCharacter", false )) {
			sendJoinResult( requester, false, "dead-character-state", "" );
			return;
		}
		int clientDepth = event.payload.optInt( "clientDepth", Dungeon.depth );
		if (Dungeon.depth > 0 && clientDepth > 0 && clientDepth != Dungeon.depth) {
			sendJoinResult( requester, false, "floor-mismatch", "" );
			return;
		}
		String presentedToken = event.payload.optString( "sessionToken", "" );
		String activeToken = sessionTokenByPeerId.get( requester );
		if (activeToken != null && activeToken.length() > 0 && !activeToken.equals( presentedToken )) {
			sendJoinResult( requester, false, "stale-token", "" );
			return;
		}
		if (activeToken == null || activeToken.length() == 0) {
			String presentedJoinKey = event.payload.optString( "joinKey", "" );
			if (joinKey != null && joinKey.length() > 0 && !joinKey.equals( presentedJoinKey )) {
				sendJoinResult( requester, false, "stale-token", "" );
				return;
			}
			activeToken = randomToken();
			sessionTokenByPeerId.put( requester, activeToken );
		}
		if (remoteHeroesByPeerId.containsKey( requester ) && presentedToken.length() == 0) {
			sendJoinResult( requester, false, "duplicate-active-id", "" );
			return;
		}
		ensureRemoteHero( requester );
		sendJoinResult( requester, true, presentedToken.length() > 0 ? "rejoined" : "joined", activeToken );
		sendFullStateSync( "rejoin:" + requester, requester, requester );
		resendLevelSyncToLateJoiner( requester );
	}

	private void sendJoinResult( String targetPlayerId, boolean accepted, String reason, String token ) {
		realtimeChannel.send( CoopEvent.joinResult(
			playerId,
			Math.max( 1, Dungeon.depth <= 0 ? 1 : Dungeon.depth ),
			targetPlayerId,
			accepted,
			reason,
			token ) );
	}

	private void handleJoinResult( CoopEvent event ) {
		if (event == null || event.payload == null) {
			return;
		}
		String targetPlayerId = event.payload.optString( "targetPlayerId", "" );
		if (!playerId.equals( targetPlayerId )) {
			return;
		}
		boolean accepted = event.payload.optBoolean( "accepted", false );
		String reason = event.payload.optString( "reason", "unspecified" );
		if (!accepted) {
			joinStatus = "Join rejected: " + reason;
			if ("stale-token".equals( reason )) {
				PixelDungeon.clearCoopSessionResume();
			}
			return;
		}
		String token = event.payload.optString( "sessionToken", "" );
		if (token.length() > 0) {
			PixelDungeon.coopSessionToken( token );
			PixelDungeon.coopSessionRoom( roomId );
		}
		joinStatus = "Connected (" + reason + ")";
	}

	private String randomToken() {
		return UUID.randomUUID().toString().replace( "-", "" );
	}

	private String computeLevelHash( Level level ) {
		CRC32 crc = new CRC32();
		updateInt( crc, Dungeon.depth );
		updateInt( crc, level.entrance );
		updateInt( crc, level.exit );
		int[] map = level.map;
		if (map != null) {
			for (int tile : map) {
				updateInt( crc, tile );
			}
		}
		for (String roomSignature : roomSignatures( level )) {
			updateBytes( crc, roomSignature );
		}
		return Long.toHexString( crc.getValue() );
	}

	private List<String> roomSignatures( Level level ) {
		ArrayList<String> signatures = new ArrayList<String>();
		if (level == null) {
			return signatures;
		}
		try {
			Field roomsField = level.getClass().getSuperclass().getDeclaredField( "rooms" );
			roomsField.setAccessible( true );
			Object roomsObj = roomsField.get( level );
			if (!(roomsObj instanceof Set<?>)) {
				return signatures;
			}
			for (Object item : (Set<?>)roomsObj) {
				if (!(item instanceof Room)) {
					continue;
				}
				Room room = (Room)item;
				signatures.add( room.left + ":" + room.top + ":" + room.right + ":" + room.bottom + ":" + room.type.name() + ":" + room.connected.size() );
			}
			Collections.sort( signatures );
		} catch (Exception ignored) {
		}
		return signatures;
	}

	private void updateInt( CRC32 crc, int value ) {
		crc.update( (value >>> 24) & 0xFF );
		crc.update( (value >>> 16) & 0xFF );
		crc.update( (value >>> 8) & 0xFF );
		crc.update( value & 0xFF );
	}

	private void updateBytes( CRC32 crc, String value ) {
		if (value == null) {
			return;
		}
		for (int i = 0; i < value.length(); i++) {
			crc.update( value.charAt( i ) );
		}
	}

	private void broadcastTurnOutcome( String actorId, CoopEvent.Kind actionKind, int fromCell, int toCell, boolean accepted, String rejectionReason ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		JSONObject payload = new JSONObject();
		payload.put( "actorId", actorId );
		payload.put( "action", actionKind == null ? "UNKNOWN" : actionKind.name() );
		payload.put( "from", fromCell );
		payload.put( "to", toCell );
		payload.put( "depth", Dungeon.depth );
		payload.put( "floorTransitionDepth", Dungeon.depth );
		RemoteHero remoteHero = remoteHeroesByPeerId.get( actorId );
		int actorPos = remoteHero != null ? remoteHero.pos : (Dungeon.hero == null ? -1 : Dungeon.hero.pos);
		payload.put( "actorPos", actorPos );
		payload.put( "hpDelta", 0 );
		payload.put( "death", false );
		payload.put( "accepted", accepted );
		if (!accepted && rejectionReason != null) {
			payload.put( "rejectionReason", rejectionReason );
		}
		realtimeChannel.send( CoopEvent.turnOutcome( playerId, Dungeon.depth, payload.toString() ) );
	}

	private void applyTurnOutcome( CoopEvent event ) {
		if (event == null || event.payload == null || event.payload.length() == 0) {
			return;
		}
		try {
			JSONObject payload = event.payload;
			String actorId = payload.optString( "actorId", null );
			if (!payload.optBoolean( "accepted", true )) {
				String reason = payload.optString( "rejectionReason", "unspecified" );
				GLog.w( "[Co-op] Intent rejected actor=%s reason=%s", actorId, reason );
				if (playerId != null && playerId.equals( actorId )) {
					snapLocalHeroToAuthoritativePosition( payload.optInt( "actorPos", Dungeon.hero == null ? -1 : Dungeon.hero.pos ) );
				}
				return;
			}
			if (playerId != null && playerId.equals( actorId )) {
				snapLocalHeroToAuthoritativePosition( payload.optInt( "actorPos", Dungeon.hero == null ? -1 : Dungeon.hero.pos ) );
				return;
			}
			RemoteHero remoteHero = ensureRemoteHero( actorId );
			if (remoteHero == null) {
				return;
			}
			int actorPos = payload.optInt( "actorPos", remoteHero.pos );
			if (isCellAvailableForRemote( remoteHero, actorPos )) {
				Actor.freeCell( remoteHero.pos );
				remoteHero.snapshotPosition( actorPos );
				Actor.occupyCell( remoteHero );
				if (remoteHero.sprite != null) {
					remoteHero.sprite.place( actorPos );
				}
			}
			if (payload.optBoolean( "death", false )) {
				deadPeerIds.add( actorId );
				despawnRemoteHero( actorId, "turn-outcome-death" );
			}
		} catch (Exception ignored) {
			GLog.w( "[Co-op] Failed to apply TURN_OUTCOME payload from %s", event.actorId );
		}
	}

	private void onResolvedAuthoritativeAction() {
		sendWorldDiff();
		turnsSinceFullStateSync++;
		turnsSinceHashBroadcast++;
		if (turnsSinceFullStateSync >= FULL_SYNC_INTERVAL_TURNS) {
			sendFullStateSync( "periodic", null, null );
			turnsSinceFullStateSync = 0;
		}
		if (turnsSinceHashBroadcast >= HASH_INTERVAL_TURNS) {
			broadcastFloorChecksum();
			turnsSinceHashBroadcast = 0;
		}
	}

	private void sendWorldDiff() {
		if (!connected || Dungeon.depth <= 0 || Dungeon.level == null) {
			return;
		}
		realtimeChannel.send( CoopEvent.worldDiff( playerId, Dungeon.depth, buildWorldDiffPayload() ) );
	}

	private void sendFullStateSync( String reason, String targetPlayerId, String actorRebindId ) {
		if (!connected || Dungeon.depth <= 0 || Dungeon.level == null) {
			return;
		}
		JSONObject state = buildFullStatePayload( actorRebindId );
		state.put( "reason", reason == null ? "unspecified" : reason );
		if (targetPlayerId != null && targetPlayerId.length() > 0) {
			state.put( "targetPlayerId", targetPlayerId );
		}
		realtimeChannel.send( CoopEvent.fullStateSync( playerId, Dungeon.depth, state ) );
	}

	private JSONObject buildFullStatePayload( String actorRebindId ) {
		JSONObject state = new JSONObject();
		state.put( "actorId", actorRebindId == null || actorRebindId.length() == 0 ? playerId : actorRebindId );
		state.put( "depth", Dungeon.depth );
		state.put( "heroPos", Dungeon.hero == null ? -1 : Dungeon.hero.pos );
		state.put( "heroHp", Dungeon.hero == null ? 0 : Dungeon.hero.HP );
		state.put( "terrain", terrainSnapshot() );
		state.put( "mobs", mobSnapshot() );
		state.put( "loot", lootSnapshot() );
		state.put( "blobs", blobSnapshot() );
		state.put( "checksum", computeFloorChecksum() );
		return state;
	}

	private JSONObject buildWorldDiffPayload() {
		JSONObject diff = new JSONObject();
		diff.put( "tickMs", System.currentTimeMillis() );
		diff.put( "mobSpawn", mobSnapshot() );
		diff.put( "mobDespawn", collectMobDespawns() );
		diff.put( "mobState", mobStateSnapshot() );
		diff.put( "lootSpawn", lootSnapshot() );
		diff.put( "lootPickup", collectLootPickup() );
		diff.put( "trapTrigger", new JSONArray() );
		diff.put( "doorState", collectDoorStateDiff() );
		diff.put( "blobUpdates", blobSnapshot() );
		return diff;
	}

	private JSONArray terrainSnapshot() {
		JSONArray terrain = new JSONArray();
		if (Dungeon.level == null || Dungeon.level.map == null) {
			return terrain;
		}
		for (int i = 0; i < Dungeon.level.map.length; i++) {
			terrain.put( Dungeon.level.map[i] );
		}
		return terrain;
	}

	private JSONArray mobSnapshot() {
		JSONArray mobs = new JSONArray();
		for (Mob mob : Dungeon.level.mobs) {
			JSONObject json = new JSONObject();
			String stableId = stableMobId( mob );
			json.put( "id", stableId );
			json.put( "class", mob.getClass().getName() );
			json.put( "pos", mob.pos );
			json.put( "hp", mob.HP );
			json.put( "ht", mob.HT );
			json.put( "state", mob.state == null ? "" : mob.state.getClass().getName() );
			mobs.put( json );
		}
		return mobs;
	}

	private JSONArray mobStateSnapshot() {
		JSONArray mobs = new JSONArray();
		for (Mob mob : Dungeon.level.mobs) {
			JSONObject json = new JSONObject();
			json.put( "id", stableMobId( mob ) );
			json.put( "pos", mob.pos );
			json.put( "hp", mob.HP );
			json.put( "ht", mob.HT );
			mobs.put( json );
		}
		return mobs;
	}

	private JSONArray collectMobDespawns() {
		JSONArray despawn = new JSONArray();
		Set<String> existingIds = new HashSet<String>();
		for (Mob mob : Dungeon.level.mobs) {
			existingIds.add( stableMobId( mob ) );
		}
		for (String stableId : new ArrayList<String>( localMobActorIdByStableId.keySet() )) {
			if (!existingIds.contains( stableId )) {
				despawn.put( stableId );
				localMobActorIdByStableId.remove( stableId );
			}
		}
		return despawn;
	}

	private JSONArray lootSnapshot() {
		JSONArray loot = new JSONArray();
		int size = Dungeon.level.heaps.size();
		for (int i = 0; i < size; i++) {
			Heap heap = Dungeon.level.heaps.valueAt( i );
			JSONObject json = new JSONObject();
			json.put( "id", stableHeapId( heap ) );
			json.put( "cell", heap.pos );
			json.put( "type", heap.type.name() );
			json.put( "size", heap.size() );
			json.put( "item", heap.peek() == null ? "" : heap.peek().toString() );
			loot.put( json );
		}
		return loot;
	}

	private JSONArray collectLootPickup() {
		JSONArray picked = new JSONArray();
		Set<String> activeHeapIds = new HashSet<String>();
		int size = Dungeon.level.heaps.size();
		for (int i = 0; i < size; i++) {
			activeHeapIds.add( stableHeapId( Dungeon.level.heaps.valueAt( i ) ) );
		}
		for (String heapId : new ArrayList<String>( remoteHeapCellByStableId.keySet() )) {
			if (!activeHeapIds.contains( heapId )) {
				JSONObject json = new JSONObject();
				json.put( "id", heapId );
				json.put( "cell", remoteHeapCellByStableId.remove( heapId ) );
				picked.put( json );
			}
		}
		return picked;
	}

	private JSONArray collectDoorStateDiff() {
		JSONArray doors = new JSONArray();
		if (Dungeon.level == null || Dungeon.level.map == null) {
			return doors;
		}
		for (int i = 0; i < Dungeon.level.map.length; i++) {
			int tile = Dungeon.level.map[i];
			if (tile == Terrain.DOOR || tile == Terrain.OPEN_DOOR || tile == Terrain.LOCKED_DOOR) {
				JSONObject json = new JSONObject();
				json.put( "cell", i );
				json.put( "tile", tile );
				doors.put( json );
			}
		}
		return doors;
	}

	private JSONArray blobSnapshot() {
		JSONArray blobs = new JSONArray();
		for (Blob blob : Dungeon.level.blobs.values()) {
			JSONObject json = new JSONObject();
			json.put( "id", blob.getClass().getName() );
			json.put( "volume", blob.volume );
			json.put( "cells", nonZeroBlobCells( blob ) );
			blobs.put( json );
		}
		return blobs;
	}

	private JSONArray nonZeroBlobCells( Blob blob ) {
		JSONArray cells = new JSONArray();
		if (blob == null || blob.cur == null) {
			return cells;
		}
		for (int i = 0; i < blob.cur.length; i++) {
			if (blob.cur[i] > 0) {
				JSONObject cell = new JSONObject();
				cell.put( "cell", i );
				cell.put( "value", blob.cur[i] );
				cells.put( cell );
			}
		}
		return cells;
	}

	private String stableMobId( Mob mob ) {
		if (mob == null) {
			return "mob-unknown";
		}
		Integer actorId = Integer.valueOf( mob.id() );
		String stableId = stableMobIdByActorId.get( actorId );
		if (stableId == null) {
			stableId = "mob-" + Dungeon.depth + "-" + actorId;
			stableMobIdByActorId.put( actorId, stableId );
		}
		localMobActorIdByStableId.put( stableId, actorId );
		return stableId;
	}

	private String stableHeapId( Heap heap ) {
		if (heap == null) {
			return "heap-unknown";
		}
		String id = "heap-" + Dungeon.depth + "-" + heap.pos;
		remoteHeapCellByStableId.put( id, Integer.valueOf( heap.pos ) );
		return id;
	}

	private void applyWorldDiff( CoopEvent event ) {
		if (event == null || event.payload == null || Dungeon.level == null) {
			return;
		}
		JSONObject diff = event.payload.optJSONObject( "diff" );
		if (diff == null) {
			return;
		}
		applyMobDiff( diff );
		applyLootDiff( diff );
		applyDoorStateDiff( diff.optJSONArray( "doorState" ) );
		applyBlobDiff( diff.optJSONArray( "blobUpdates" ) );
		GameScene.updateMap();
		GameScene.afterObserve();
	}

	private void applyMobDiff( JSONObject diff ) {
		JSONArray mobState = diff.optJSONArray( "mobState" );
		if (mobState == null) {
			requestSnapshotResync( "missing-mob-state" );
			return;
		}
		for (int i = 0; i < mobState.length(); i++) {
			JSONObject mobJson = mobState.optJSONObject( i );
			if (mobJson == null) {
				continue;
			}
			String stableId = mobJson.optString( "id", "" );
			Mob localMob = localMobByStableId( stableId );
			if (localMob == null) {
				requestSnapshotResync( "missing-mob:" + stableId );
				continue;
			}
			int newPos = mobJson.optInt( "pos", localMob.pos );
			if (isInBounds( newPos ) && localMob.pos != newPos && isCellAvailableForRemote( localMob, newPos )) {
				int oldPos = localMob.pos;
				Actor.freeCell( oldPos );
				localMob.pos = newPos;
				Actor.occupyCell( localMob );
				if (localMob.sprite != null) {
					localMob.sprite.move( oldPos, newPos );
				}
			}
			localMob.HP = mobJson.optInt( "hp", localMob.HP );
			if (localMob.sprite != null) {
				localMob.sprite.showStatus( CharSprite.NEUTRAL, String.valueOf( localMob.HP ) );
			}
		}
		JSONArray despawn = diff.optJSONArray( "mobDespawn" );
		if (despawn != null) {
			for (int i = 0; i < despawn.length(); i++) {
				String stableId = despawn.optString( i, null );
				Mob mob = localMobByStableId( stableId );
				if (mob != null) {
					Dungeon.level.mobs.remove( mob );
					mob.destroy();
				}
			}
		}
	}

	private Mob localMobByStableId( String stableId ) {
		if (stableId == null || stableId.length() == 0) {
			return null;
		}
		Integer actorId = localMobActorIdByStableId.get( stableId );
		if (actorId == null) {
			return null;
		}
		for (Mob mob : Dungeon.level.mobs) {
			if (mob.id() == actorId.intValue()) {
				return mob;
			}
		}
		return null;
	}

	private void applyLootDiff( JSONObject diff ) {
		JSONArray pickup = diff.optJSONArray( "lootPickup" );
		if (pickup != null) {
			for (int i = 0; i < pickup.length(); i++) {
				JSONObject pickupJson = pickup.optJSONObject( i );
				if (pickupJson == null) {
					continue;
				}
				int cell = pickupJson.optInt( "cell", -1 );
				Heap heap = Dungeon.level.heaps.get( cell );
				if (heap != null) {
					heap.destroy();
				}
			}
		}
	}

	private void applyDoorStateDiff( JSONArray doors ) {
		if (doors == null || Dungeon.level == null || Dungeon.level.map == null) {
			return;
		}
		for (int i = 0; i < doors.length(); i++) {
			JSONObject door = doors.optJSONObject( i );
			if (door == null) {
				continue;
			}
			int cell = door.optInt( "cell", -1 );
			int tile = door.optInt( "tile", -1 );
			if (isInBounds( cell ) && tile >= 0 && Dungeon.level.map[cell] != tile) {
				Dungeon.level.map[cell] = tile;
				GameScene.updateMap( cell );
			}
		}
	}

	private void applyBlobDiff( JSONArray blobs ) {
		if (blobs == null || Dungeon.level == null) {
			return;
		}
		for (int i = 0; i < blobs.length(); i++) {
			JSONObject blobJson = blobs.optJSONObject( i );
			if (blobJson == null) {
				continue;
			}
			String className = blobJson.optString( "id", "" );
			Blob blob = findBlobByClassName( className );
			if (blob == null) {
				continue;
			}
			JSONArray cells = blobJson.optJSONArray( "cells" );
			if (cells == null) {
				continue;
			}
			for (int c = 0; c < blob.cur.length; c++) {
				blob.cur[c] = 0;
			}
			blob.volume = 0;
			for (int c = 0; c < cells.length(); c++) {
				JSONObject cell = cells.optJSONObject( c );
				if (cell == null) {
					continue;
				}
				int index = cell.optInt( "cell", -1 );
				int value = cell.optInt( "value", 0 );
				if (isInBounds( index ) && value > 0) {
					blob.cur[index] = value;
					blob.volume += value;
				}
			}
		}
	}

	private Blob findBlobByClassName( String className ) {
		for (Blob blob : Dungeon.level.blobs.values()) {
			if (blob.getClass().getName().equals( className )) {
				return blob;
			}
		}
		return null;
	}

	private void broadcastFloorChecksum() {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.levelHash( playerId, Dungeon.depth, computeFloorChecksum() ) );
	}

	private String computeFloorChecksum() {
		String base = computeLevelHash( Dungeon.level );
		return Dungeon.depth + ":" + base + ":" + Dungeon.level.mobs.size() + ":" + Dungeon.level.heaps.size() + ":" + Dungeon.level.blobs.size();
	}

	private void requestSnapshotResync( String reason ) {
		if (!connected || Dungeon.depth <= 0) {
			return;
		}
		realtimeChannel.send( CoopEvent.snapshotRequest( playerId, Dungeon.depth, reason ) );
	}

	private boolean isIntentKind( CoopEvent.Kind kind ) {
		return kind == CoopEvent.Kind.MOVE
			|| kind == CoopEvent.Kind.ATTACK
			|| kind == CoopEvent.Kind.USE
			|| kind == CoopEvent.Kind.ITEM_PICKUP
			|| kind == CoopEvent.Kind.ITEM_DROP
			|| kind == CoopEvent.Kind.ITEM_USE
			|| kind == CoopEvent.Kind.DOOR_UNLOCK
			|| kind == CoopEvent.Kind.DESCEND
			|| kind == CoopEvent.Kind.ASCEND;
	}

	private String validateIntent( RemoteHero remoteHero, CoopEvent intent ) {
		if (remoteHero == null || intent == null || intent.payload == null) {
			return "intent or actor missing";
		}
		Long lastAcceptedMs = lastAcceptedIntentMsByPeerId.get( remoteHero.peerId );
		long now = System.currentTimeMillis();
		if (lastAcceptedMs != null && now - lastAcceptedMs.longValue() < INTENT_MIN_INTERVAL_MS) {
			return "cooldown active";
		}
		if (intent.depth != Dungeon.depth) {
			return "depth mismatch";
		}
		if (intent.kind == CoopEvent.Kind.MOVE) {
			if (!isInBounds( intent.fromCell ) || !isInBounds( intent.toCell )) {
				return "move range invalid";
			}
			if (!isValidMovement( remoteHero, remoteHero.pos, intent.toCell )) {
				return "illegal move tile";
			}
			return null;
		}
		if (intent.kind == CoopEvent.Kind.ATTACK || intent.kind == CoopEvent.Kind.USE) {
			if (!isInBounds( intent.toCell )) {
				return "target range invalid";
			}
			if (!Level.adjacent( remoteHero.pos, intent.toCell )) {
				return "target not adjacent";
			}
			return null;
		}
		if (intent.kind == CoopEvent.Kind.ITEM_PICKUP) {
			int cell = intent.payload.optInt( "cell", -1 );
			if (!isInBounds( cell )) {
				return "pickup cell out of bounds";
			}
			if (!(cell == remoteHero.pos || Level.adjacent( remoteHero.pos, cell ))) {
				return "pickup out of range";
			}
			return null;
		}
		if (intent.kind == CoopEvent.Kind.ITEM_DROP || intent.kind == CoopEvent.Kind.ITEM_USE) {
			String itemName = intent.payload.optString( "item", "" );
			if (itemName.length() == 0) {
				return "missing item name";
			}
			if (!peerInventory( remoteHero.peerId ).contains( normalizeItemName( itemName ) )) {
				return "item not owned";
			}
			return null;
		}
		if (intent.kind == CoopEvent.Kind.DOOR_UNLOCK) {
			int cell = intent.payload.optInt( "cell", -1 );
			if (!isInBounds( cell )) {
				return "door cell out of bounds";
			}
			if (!Level.adjacent( remoteHero.pos, cell )) {
				return "door not adjacent";
			}
			if (Dungeon.level == null || Dungeon.level.map[cell] != Terrain.LOCKED_DOOR) {
				return "door tile is not locked";
			}
			return null;
		}
		if (intent.kind == CoopEvent.Kind.DESCEND) {
			int cell = intent.payload.optInt( "cell", -1 );
			if (Dungeon.level == null || cell != Dungeon.level.exit || remoteHero.pos != cell) {
				return "cannot descend from this tile";
			}
			return null;
		}
		if (intent.kind == CoopEvent.Kind.ASCEND) {
			int cell = intent.payload.optInt( "cell", -1 );
			if (Dungeon.level == null || cell != Dungeon.level.entrance || remoteHero.pos != cell) {
				return "cannot ascend from this tile";
			}
			return null;
		}
		return null;
	}

	private void applyInventoryMutation( String peerId, CoopEvent intent ) {
		if (peerId == null || intent == null || intent.payload == null) {
			return;
		}
		String itemName = normalizeItemName( intent.payload.optString( "item", "" ) );
		if (itemName.length() == 0) {
			return;
		}
		Set<String> inventory = peerInventory( peerId );
		if (intent.kind == CoopEvent.Kind.ITEM_PICKUP) {
			inventory.add( itemName );
		} else if (intent.kind == CoopEvent.Kind.ITEM_DROP || intent.kind == CoopEvent.Kind.ITEM_USE) {
			inventory.remove( itemName );
		}
	}

	private Set<String> peerInventory( String peerId ) {
		Set<String> inventory = inventoryByPeerId.get( peerId );
		if (inventory == null) {
			inventory = new HashSet<String>();
			inventoryByPeerId.put( peerId, inventory );
		}
		return inventory;
	}

	private String normalizeItemName( String itemName ) {
		return itemName == null ? "" : itemName.trim().toLowerCase();
	}

	private boolean isInBounds( int cell ) {
		return cell >= 0 && cell < Level.LENGTH;
	}

	private void rejectIntent( String peerId, CoopEvent intent, String reason ) {
		String actor = peerId == null ? "unknown" : peerId;
		String kind = intent == null || intent.kind == null ? "UNKNOWN" : intent.kind.name();
		String msg = "[Co-op][IntentReject] actor=" + actor + " kind=" + kind + " reason=" + reason;
		rejectedIntentLog.addLast( msg );
		while (rejectedIntentLog.size() > MAX_REJECT_LOG_ENTRIES) {
			rejectedIntentLog.pollFirst();
		}
		GLog.w( msg );
	}

	private void rejectStateMutation( CoopEvent event, String reason ) {
		if (event == null) {
			return;
		}
		rejectIntent( event.actorId, event, reason );
	}

	private boolean isEventFromAuthoritativeHost( CoopEvent event ) {
		if (event == null || event.actorId == null) {
			return false;
		}
		if (isHost() && playerId != null) {
			return playerId.equals( event.actorId );
		}
		if (authoritativePeerId == null || authoritativePeerId.length() == 0) {
			authoritativePeerId = event.actorId;
		}
		return authoritativePeerId.equals( event.actorId );
	}

	private void snapLocalHeroToAuthoritativePosition( int authoritativePos ) {
		if (Dungeon.hero == null || !isInBounds( authoritativePos )) {
			return;
		}
		if (Dungeon.hero.pos == authoritativePos) {
			return;
		}
		int oldPos = Dungeon.hero.pos;
		Actor.freeCell( oldPos );
		Dungeon.hero.pos = authoritativePos;
		Actor.occupyCell( Dungeon.hero );
		if (Dungeon.hero.sprite != null) {
			Dungeon.hero.sprite.place( authoritativePos );
			Dungeon.hero.sprite.showStatus( CharSprite.WARNING, "SYNC" );
		}
		GLog.w( "[Co-op] Local prediction corrected %d->%d", Integer.valueOf( oldPos ), Integer.valueOf( authoritativePos ) );
	}

	private void applyAuthoritativeState( CoopEvent event ) {
		if (event == null || event.payload == null) {
			return;
		}
		JSONObject state = event.payload.optJSONObject( "state" );
		if (state == null) {
			return;
		}
		String targetPlayerId = state.optString( "targetPlayerId", "" );
		if (targetPlayerId.length() > 0 && !targetPlayerId.equals( playerId )) {
			return;
		}
		if (Dungeon.depth > 0 && state.optInt( "depth", Dungeon.depth ) != Dungeon.depth) {
			joinStatus = "Join rejected: floor-mismatch";
			return;
		}
		if (state.optInt( "heroHp", 1 ) <= 0) {
			joinStatus = "Join rejected: dead-character-state";
			return;
		}
		String actorRebindId = state.optString( "actorId", "" );
		if (actorRebindId.length() > 0 && !actorRebindId.equals( playerId )) {
			joinStatus = "Join rejected: duplicate-active-id";
			return;
		}
		int heroPos = state.optInt( "heroPos", state.optInt( "actorPos", -1 ) );
		snapLocalHeroToAuthoritativePosition( heroPos );
		if (Dungeon.hero != null) {
			Dungeon.hero.HP = state.optInt( "heroHp", Dungeon.hero.HP );
		}
		joinStatus = "Rejoin synchronized";
		JSONArray terrain = state.optJSONArray( "terrain" );
		if (terrain != null && Dungeon.level != null && Dungeon.level.map != null && terrain.length() == Dungeon.level.map.length) {
			for (int i = 0; i < terrain.length(); i++) {
				Dungeon.level.map[i] = terrain.optInt( i, Dungeon.level.map[i] );
			}
			GameScene.updateMap();
		}
		applyWorldDiff( new CoopEvent( event.version, CoopEvent.Kind.WORLD_DIFF, CoopEvent.Kind.WORLD_DIFF.name(), event.actorId, event.floor, event.tick,
			new JSONObject().put( "diff", new JSONObject()
				.put( "mobState", state.optJSONArray( "mobs" ) == null ? new JSONArray() : state.optJSONArray( "mobs" ) )
				.put( "mobDespawn", new JSONArray() )
				.put( "lootPickup", new JSONArray() )
				.put( "doorState", collectDoorStateDiff() )
				.put( "blobUpdates", state.optJSONArray( "blobs" ) == null ? new JSONArray() : state.optJSONArray( "blobs" ) ) ),
			event.sentAtMs ) );
	}
}
