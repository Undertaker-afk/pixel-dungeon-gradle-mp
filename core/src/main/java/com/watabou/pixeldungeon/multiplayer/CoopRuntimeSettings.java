package com.watabou.pixeldungeon.multiplayer;

public class CoopRuntimeSettings {

	private final CoopSimulationPolicy simulationPolicy;

	public CoopRuntimeSettings( CoopSimulationPolicy simulationPolicy ) {
		this.simulationPolicy = simulationPolicy == null ? CoopSimulationPolicy.HOST_AUTHORITATIVE : simulationPolicy;
	}

	public CoopSimulationPolicy simulationPolicy() {
		return simulationPolicy;
	}
}
