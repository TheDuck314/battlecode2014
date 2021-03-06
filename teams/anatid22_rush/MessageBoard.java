package anatid22_rush;

import battlecode.common.*;

public enum MessageBoard {
	RALLY_LOC(GameConstants.BROADCAST_MAX_CHANNELS - 1),
	BE_AGGRESSIVE(GameConstants.BROADCAST_MAX_CHANNELS - 2),
	ROUND_KILL_COUNT(GameConstants.BROADCAST_MAX_CHANNELS - 3),
	SPAWN_COUNT(GameConstants.BROADCAST_MAX_CHANNELS - 4),
	STRATEGY(GameConstants.BROADCAST_MAX_CHANNELS - 5),
	PASTR_DISTRESS_SIGNAL(GameConstants.BROADCAST_MAX_CHANNELS - 6),
	SELF_DESTRUCT_LOCKOUT_ID(GameConstants.BROADCAST_MAX_CHANNELS - 7),
	SELF_DESTRUCT_LOCKOUT_ROUND(GameConstants.BROADCAST_MAX_CHANNELS - 8),
	NUM_PASTR_LOCATIONS(GameConstants.BROADCAST_MAX_CHANNELS - 9),
	NUM_SUPPRESSORS(GameConstants.BROADCAST_MAX_CHANNELS - 10),
	BEST_PASTR_LOCATIONS(GameConstants.BROADCAST_MAX_CHANNELS - 20),
	TOWER_BUILDER_ROBOT_IDS(GameConstants.BROADCAST_MAX_CHANNELS - 30),
	PASTR_BUILDER_ROBOT_IDS(GameConstants.BROADCAST_MAX_CHANNELS - 40),
	SUPPRESSOR_TARGET_LOCATIONS(GameConstants.BROADCAST_MAX_CHANNELS - 50),
	SUPPRESSOR_BUILDER_ROBOT_IDS(GameConstants.BROADCAST_MAX_CHANNELS - 60);

	public static void setDefaultChannelValues() throws GameActionException {
		RALLY_LOC.writeMapLocation(null);
		BE_AGGRESSIVE.writeBoolean(false);
		ROUND_KILL_COUNT.writeInt(0);
		SPAWN_COUNT.writeInt(0);
		STRATEGY.writeStrategy(Strategy.UNDECIDED);
		PASTR_DISTRESS_SIGNAL.writeInt(0);
		SELF_DESTRUCT_LOCKOUT_ID.writeInt(-1);
		SELF_DESTRUCT_LOCKOUT_ROUND.writeInt(0);
		NUM_PASTR_LOCATIONS.writeInt(0);
		NUM_SUPPRESSORS.writeInt(0);
		for (int i = 0; i < BotHQ.MAX_PASTR_LOCATIONS; i++) {
			BEST_PASTR_LOCATIONS.writeToMapLocationList(i, null);
			TOWER_BUILDER_ROBOT_IDS.clearAssignment(i);
			PASTR_BUILDER_ROBOT_IDS.clearAssignment(i);
		}
		for (int i = 0; i < BotHQ.MAX_SUPPRESSORS; i++) {
			SUPPRESSOR_TARGET_LOCATIONS.writeToMapLocationList(i, null);
			SUPPRESSOR_BUILDER_ROBOT_IDS.clearAssignment(i);
		}
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

	public void writeBoolean(boolean bool) throws GameActionException {
		writeInt(bool ? 1 : 0);
	}

	public boolean readBoolean() throws GameActionException {
		return readInt() == 1;
	}

	public void writeMapLocation(MapLocation loc) throws GameActionException {
		int data = (loc == null ? -999 : loc.x * GameConstants.MAP_MAX_WIDTH + loc.y);
		writeInt(data);
	}

	public MapLocation readMapLocation() throws GameActionException {
		int data = readInt();
		if (data == -999) return null;
		else return new MapLocation(data / GameConstants.MAP_MAX_WIDTH, data % GameConstants.MAP_MAX_WIDTH);
	}

	public void writeStrategy(Strategy s) throws GameActionException {
		writeInt(s.ordinal());
	}

	public Strategy readStrategy() throws GameActionException {
		return Strategy.values()[readInt()];
	}

	public void writeToMapLocationList(int index, MapLocation loc) throws GameActionException {
		int data = (loc == null ? -999 : loc.x * GameConstants.MAP_MAX_HEIGHT + loc.y);
		rc.broadcast(channel + index, data);
	}

	public MapLocation readFromMapLocationList(int index) throws GameActionException {
		int data = rc.readBroadcast(channel + index);
		if (data == -999) return null;
		else return new MapLocation(data / GameConstants.MAP_MAX_HEIGHT, data % GameConstants.MAP_MAX_HEIGHT);
	}

	public void claimAssignment(int index) throws GameActionException {
		rc.broadcast(channel + index, rc.getRobot().getID());
	}

	public void clearAssignment(int index) throws GameActionException {
		rc.broadcast(channel + index, -1);
	}

	public boolean checkIfIOwnAssignment(int index) throws GameActionException {
		return rc.readBroadcast(channel + index) == rc.getRobot().getID();
	}

	public boolean checkIfAssignmentUnowned(int index) throws GameActionException {
		return rc.readBroadcast(channel + index) == -1;
	}

	public int readCurrentAssignedID(int index) throws GameActionException {
		return rc.readBroadcast(channel + index);
	}
}
