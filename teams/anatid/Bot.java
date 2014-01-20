package anatid;

import battlecode.common.*;

public class Bot {
	static RobotController rc;
	static Team us, them;
	static MapLocation ourHQ, theirHQ;

	protected static void init(RobotController theRC) throws GameActionException {
		rc = theRC;
		us = rc.getTeam();
		them = us.opponent();

		ourHQ = rc.senseHQLocation();
		theirHQ = rc.senseEnemyHQLocation();

		FastRandom.init();
		MessageBoard.init(theRC);
		Bfs.init(theRC);
	}
}
