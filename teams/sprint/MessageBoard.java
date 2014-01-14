package sprint;

import battlecode.common.*;

public enum MessageBoard {
	BEST_PASTR_LOC(GameConstants.BROADCAST_MAX_CHANNELS - 1),
	ATTACK_LOC(GameConstants.BROADCAST_MAX_CHANNELS - 2),
	NOISE_TOWER_BUILD_LOCATION(GameConstants.BROADCAST_MAX_CHANNELS - 3),
	NOISE_TOWER_BUILD_START_ROUND(GameConstants.BROADCAST_MAX_CHANNELS - 4),
	ROUND_KILL_COUNT(GameConstants.BROADCAST_MAX_CHANNELS - 5),
	SPAWN_COUNT(GameConstants.BROADCAST_MAX_CHANNELS - 6),
	STRATEGY(GameConstants.BROADCAST_MAX_CHANNELS - 7),
	REBUILDING_HQ_PASTR_ROUND_START(GameConstants.BROADCAST_MAX_CHANNELS - 8);

	public static void setDefaultChannelValues() throws GameActionException {
		BEST_PASTR_LOC.writeMapLocation(null);
		ATTACK_LOC.writeMapLocation(null);
		NOISE_TOWER_BUILD_LOCATION.writeMapLocation(null);
		NOISE_TOWER_BUILD_START_ROUND.writeInt(0);
		ROUND_KILL_COUNT.writeInt(0);
		SPAWN_COUNT.writeInt(0);
		STRATEGY.writeStrategy(Strategy.UNDECIDED);
		REBUILDING_HQ_PASTR_ROUND_START.writeInt(0);
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
	
	public void writeStrategy(Strategy s) throws GameActionException {
		writeInt(s.ordinal());
	}
	
	public Strategy readStrategy() throws GameActionException {
		return Strategy.values()[readInt()];
	}
}
