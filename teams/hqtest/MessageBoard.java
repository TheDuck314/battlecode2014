package hqtest;

import battlecode.common.*;

public enum MessageBoard {
	BEST_PASTR_LOC(GameConstants.BROADCAST_MAX_CHANNELS - 1),
	ATTACK_LOC(GameConstants.BROADCAST_MAX_CHANNELS - 2),
	BUILDING_NOISE_TOWER(GameConstants.BROADCAST_MAX_CHANNELS - 3),
	ROUND_KILL_COUNT(GameConstants.BROADCAST_MAX_CHANNELS - 4),
	CAMP_SPAWN_TARGET(GameConstants.BROADCAST_MAX_CHANNELS - 5);	

	private final int channel;

	private MessageBoard(int theChannel) {
		channel = theChannel;
	}

	public void writeInt(int data, RobotController rc) throws GameActionException {
		rc.broadcast(channel, data);
	}

	public int readInt(RobotController rc) throws GameActionException {
		return rc.readBroadcast(channel);
	}
	
	public void incrementInt(RobotController rc) throws GameActionException {
		writeInt(1 + readInt(rc), rc);
	}

	public void writeMapLocation(MapLocation loc, RobotController rc) throws GameActionException {
		int data = (loc == null ? -999 : loc.x * GameConstants.MAP_MAX_WIDTH + loc.y);
		writeInt(data, rc);
	}

	public MapLocation readMapLocation(RobotController rc) throws GameActionException {
		int data = readInt(rc);
		if(data == -999) return null;
		else return new MapLocation(data / GameConstants.MAP_MAX_WIDTH, data % GameConstants.MAP_MAX_WIDTH);
	}
}
