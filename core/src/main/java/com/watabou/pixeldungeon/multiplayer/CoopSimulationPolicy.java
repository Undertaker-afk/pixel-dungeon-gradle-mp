package com.watabou.pixeldungeon.multiplayer;

public enum CoopSimulationPolicy {
	HOST_AUTHORITATIVE,
	LOCKSTEP;

	public String wireName() {
		return name().toLowerCase();
	}

	public static CoopSimulationPolicy fromWireName( String value ) {
		if (value == null) {
			return HOST_AUTHORITATIVE;
		}
		String normalized = value.trim().toUpperCase().replace( '-', '_' );
		try {
			return CoopSimulationPolicy.valueOf( normalized );
		} catch (IllegalArgumentException ignored) {
			return HOST_AUTHORITATIVE;
		}
	}
}
