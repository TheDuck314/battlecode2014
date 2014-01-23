package anatid;

public enum Strategy {
	ONE_PASTR, // Build a noise tower then pastr somewhere nice
	ONE_PASTR_SUPPRESSOR, // Build a noise tower then pastr somewhere nice, and when the opponent builds a pastr build a suppressing noise tower
	HQ_PASTR, // Build a noise tower then pastr in HQ and harass with soldiers
	PROXY, // Proxy 2 Gate's strategy from sprint: rally to center, once enemy builds pastr stand around it and build your own noise tower + pastr
	PROXY_ATTACK, // Like PROXY but we try to attack and destroy the enemy's pastr if we have the numbers for it
	SCATTER, // Try to build several noise tower + pastrs around the map, all while harrassing the enemy's pastr(s)
	SCATTER_SUPPRESSOR, // like SCATTER but builds a suppressor tower too
	RUSH, // rally to the center and then attack pastrs as they appear
	UNDECIDED;

	public static Strategy active = UNDECIDED;
}
