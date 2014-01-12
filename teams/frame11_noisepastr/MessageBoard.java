package frame11_noisepastr;

import battlecode.common.*;

public enum MessageBoard {
	BEST_PASTR_LOC(GameConstants.BROADCAST_MAX_CHANNELS - 1),
	ATTACK_LOC(GameConstants.BROADCAST_MAX_CHANNELS - 2),
	BUILDING_NOISE_TOWER(GameConstants.BROADCAST_MAX_CHANNELS - 3),
	ROUND_KILL_COUNT(GameConstants.BROADCAST_MAX_CHANNELS - 4),
	SPAWN_COUNT(GameConstants.BROADCAST_MAX_CHANNELS - 5);

	public static void setDefaultChannelValues() throws GameActionException {
		BEST_PASTR_LOC.writeMapLocation(null);
		ATTACK_LOC.writeMapLocation(null);
		BUILDING_NOISE_TOWER.writeMapLocation(null);
		ROUND_KILL_COUNT.writeInt(0);
		SPAWN_COUNT.writeInt(0);
	}
	
	private static RobotController rc;
	
	public static void init(RobotController theRC) {
		rc = theRC;
	}
	
	private final int channel;

	private MessageBoard(int theChannel) {
		channel = theChannel;
	}

	public void writeInt(int data) throws GameActionException {
		rc.broadcast(channel, data);
	}

	public int readInt() throws GameActionException {
		return rc.readBroadcast(channel);
	}
	
	public void incrementInt() throws GameActionException {
		writeInt(1 + readInt());
	}

	public void writeMapLocation(MapLocation loc) throws GameActionException {
		int data = (loc == null ? -999 : loc.x * GameConstants.MAP_MAX_WIDTH + loc.y);
		writeInt(data);
	}

	public MapLocation readMapLocation() throws GameActionException {
		int data = readInt();
		if(data == -999) return null;
		else return new MapLocation(data / GameConstants.MAP_MAX_WIDTH, data % GameConstants.MAP_MAX_WIDTH);
	}
}
