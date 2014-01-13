package frame11_rush;

public enum Strategy {
	ONE_PASTR_THEN_NOISE, // Build a pastr somewhere nice, then a noise tower when it seems safe
	NOISE_THEN_ONE_PASTR, // Build a noise tower somewhere nice, then a pastr, then defend them
	HQ_PASTR, // Build a noise tower then pastr in HQ and harass with soldiers
	RUSH, // Rally to the middle of the map and rush pastrs as they appear
	MACRO; // Build several pastrs in nice places (with noise towers??)

	public static Strategy active = RUSH;
}
