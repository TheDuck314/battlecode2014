package framework;

import battlecode.common.*;

public class BotHQ extends Bot {
	public BotHQ(RobotController theRC) {
		super(theRC);
	}

	// Strategic info
	int virtualSpawnCountdown = 0;
	int maxEnemySpawns;
	int numEnemyPastrs;
	int maxEnemySoldiers; //An upper bound on the # of enemy soldiers
	double ourMilk;
	double theirMilk;
	

	// Guess how many bots the opponent has
	// TODO: account for pop cap
	// TODO: record kills of enemy units
	private void updateStrategicInfo() {
		numEnemyPastrs = rc.sensePastrLocations(them).length;
		virtualSpawnCountdown--;
		if (virtualSpawnCountdown <= 0) {
			maxEnemySpawns++;
			int maxEnemyPopCount = maxEnemySpawns + numEnemyPastrs;
			virtualSpawnCountdown = (int) Math.round(GameConstants.HQ_SPAWN_DELAY_CONSTANT_1
					+ Math.pow(maxEnemyPopCount - 1, GameConstants.HQ_SPAWN_DELAY_CONSTANT_2));
		}
		maxEnemySoldiers = maxEnemySpawns - numEnemyPastrs;
		
		ourMilk = rc.senseTeamMilkQuantity(us);
		theirMilk = rc.senseTeamMilkQuantity(them);
	}

	public void turn() throws GameActionException {
		updateStrategicInfo();

		System.out.format("milk: us = %f, them = %f\n", rc.senseTeamMilkQuantity(us), rc.senseTeamMilkQuantity(them));

		if (!rc.isActive()) return;

		if (attackEnemies()) return;
		spawnSoldier();
	}

	private boolean attackEnemies() throws GameActionException {
		Robot[] enemies = rc.senseNearbyGameObjects(Robot.class, RobotType.HQ.attackRadiusMaxSquared, them);
		if (enemies.length == 0) return false;

		RobotInfo info = rc.senseRobotInfo(enemies[0]);
		MapLocation target = info.location;
		rc.attackSquare(target);
		return true;
	}

	private boolean spawnSoldier() throws GameActionException {
		if (rc.senseRobotCount() >= 2 /* GameConstants.MAX_ROBOTS */) return false;

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
