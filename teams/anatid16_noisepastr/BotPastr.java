package anatid16_noisepastr;

import battlecode.common.*;

public class BotPastr extends Bot {
	public BotPastr(RobotController theRC) {
		super(theRC);
		Debug.init(theRC, "pages");
	}

	public void turn() throws GameActionException {
		// do speculative pathing calculations
		int bytecodeLimit = 9000;
		MapLocation[] enemyPastrs = rc.sensePastrLocations(them);
		for (int i = 0; i < enemyPastrs.length; i++) {
			if (Clock.getBytecodeNum() > bytecodeLimit) break;
			Bfs.work(enemyPastrs[i], rc, Bfs.PRIORITY_LOW, bytecodeLimit);
		}
	}

}
