package zephyr;

import battlecode.common.*;

public class BotPastr extends Bot {
	public static void loop(RobotController theRC) throws Exception {
		init(theRC);
		while (true) {
			try {
				turn();
			} catch (Exception e) {
				e.printStackTrace();
			}
			rc.yield();
		}
	}

	protected static void init(RobotController theRC) throws GameActionException {
		Bot.init(theRC);
		// Debug.init(theRC, "pages");

		// claim the assignment to build this pastr so others know not to build it
		int numPastrLocations = MessageBoard.NUM_PASTR_LOCATIONS.readInt();
		for (int i = 0; i < numPastrLocations; i++) {
			MapLocation pastrLoc = MessageBoard.BEST_PASTR_LOCATIONS.readFromMapLocationList(i);
			if (rc.getLocation().equals(pastrLoc)) {
				MessageBoard.PASTR_BUILDER_ROBOT_IDS.claimAssignment(i);
				break;
			}
		}
	}

	static double lastHealth = RobotType.PASTR.maxHealth;

	private static void turn() throws GameActionException {
		if (rc.getHealth() < lastHealth) {
			MessageBoard.COLLAPSE_TO_PASTR_SIGNAL.writeInt(Clock.getRoundNum() + 30);
		}
		lastHealth = rc.getHealth();

		if (rc.getHealth() <= 6 * RobotType.SOLDIER.attackPower) {
			MessageBoard.PASTR_DISTRESS_SIGNAL.writeInt(Clock.getRoundNum());
		}

		// do speculative pathing calculations
		int bytecodeLimit = 9000;
		MapLocation[] enemyPastrs = rc.sensePastrLocations(them);
		for (int i = 0; i < enemyPastrs.length; i++) {
			if (Clock.getBytecodeNum() > bytecodeLimit) break;
			Bfs.work(enemyPastrs[i], Bfs.PRIORITY_LOW, bytecodeLimit);
		}
	}

}
