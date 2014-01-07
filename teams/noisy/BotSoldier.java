package noisy;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public BotSoldier(RobotController theRC) {
		super(theRC);
	}

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;

		if (buildPastr()) return;
		if (buildNoiseTower()) return;
		if (attackEnemy()) return;
		//moveRandomly();

	}

	boolean buildPastr() throws GameActionException {
		if (Clock.getRoundNum() != 0) return false;
		rc.construct(RobotType.PASTR);
		return true;
	}

	boolean buildNoiseTower() throws GameActionException {
		if (Clock.getRoundNum() != 30) return false;
		rc.construct(RobotType.NOISETOWER);
		return true;
	}

	boolean attackEnemy() throws GameActionException {
		Robot[] enemies = rc.senseNearbyGameObjects(Robot.class, RobotType.SOLDIER.attackRadiusMaxSquared, them);
		if (enemies.length == 0) return false;

		RobotInfo info = rc.senseRobotInfo(enemies[0]);
		MapLocation target = info.location;
		rc.attackSquare(target);
		return true;
	}

	boolean moveRandomly() throws GameActionException {
		Direction dir = Direction.values()[(int) (8 * Math.random())];
		if (rc.canMove(dir)) {
			rc.move(dir);
			return true;
		} else {
			return false;
		}
	}
}
