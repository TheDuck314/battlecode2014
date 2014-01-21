package anatid16_proxy;

public enum Strategy {
	ONE_PASTR_THEN_NOISE, // Build a pastr somewhere nice, then a noise tower when it seems safe
	NOISE_THEN_ONE_PASTR, // Build a noise tower somewhere nice, then a pastr, then defend them
	HQ_PASTR, // Build a noise tower then pastr in HQ and harass with soldiers
	RUSH, // Rally to the middle of the map and rush pastrs as they appear
	MACRO, // Build several pastrs in nice places (with noise towers??)
	PROXY, // Proxy 2 Gate's strategy from sprint: rally to center, one enemy builds pastr stand around it and build your own noise tower + pastr
	UNDECIDED;

	public static Strategy active = UNDECIDED;
}
