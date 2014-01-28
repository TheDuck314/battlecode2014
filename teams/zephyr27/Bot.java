package zephyr27;

import battlecode.common.*;

public class Bot {
	static RobotController rc;
	static Team us, them;
	static MapLocation ourHQ, theirHQ;
	static int mapWidth, mapHeight;

	protected static void init(RobotController theRC) throws GameActionException {
		rc = theRC;
		us = rc.getTeam();
		them = us.opponent();

		ourHQ = rc.senseHQLocation();
		theirHQ = rc.senseEnemyHQLocation();
		
		mapWidth = rc.getMapWidth();
		mapHeight = rc.getMapHeight();
		
		FastRandom.init();
		MessageBoard.init(theRC);
		Bfs.init(theRC);
	}
	
	public static boolean isInTheirHQAttackRange(MapLocation loc) {
		int distSq = theirHQ.distanceSquaredTo(loc);
		if (distSq < 25) return true;
		else if (distSq > 25) return false;
		else return (loc.x != theirHQ.x) && (loc.y != theirHQ.y);
	}

	public static boolean isInOurHQAttackRange(MapLocation loc) {
		int distSq = ourHQ.distanceSquaredTo(loc);
		if (distSq < 25) return true;
		else if (distSq > 25) return false;
		else return (loc.x != ourHQ.x) && (loc.y != ourHQ.y);	}
	
	public static boolean isOnMap(MapLocation loc) {
		return loc.x >= 0 && loc.y >= 0 && loc.x < mapWidth && loc.y < mapHeight;
	}
}
