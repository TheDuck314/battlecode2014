package framework;

import battlecode.common.*;

public class BotHQ extends Bot {
	public BotHQ(RobotController theRC) {
		super(theRC);
	}

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;

		if (attackEnemies()) return;
		spawnSoldier();
	}

	boolean attackEnemies() throws GameActionException {
		Robot[] enemies = rc.senseNearbyGameObjects(Robot.class, RobotType.HQ.attackRadiusMaxSquared, them);
		if (enemies.length == 0) return false;

		RobotInfo info = rc.senseRobotInfo(enemies[0]);
		MapLocation target = info.location;
		rc.attackSquare(target);
		return true;
	}

	boolean spawnSoldier() throws GameActionException {
		if (rc.senseRobotCount() >= 2 /*GameConstants.MAX_ROBOTS*/) return false;

		Direction dir = ourHQ.directionTo(theirHQ);
		for (int i = 8; i-- > 0;) {
			if (rc.canMove(dir)) {
				rc.spawn(dir);
				return true;
			} else {
				dir = dir.rotateRight();
			}
		}

		return false;
	}
}
