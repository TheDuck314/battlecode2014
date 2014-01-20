package anatid;

import battlecode.common.*;

public abstract class Bot {
	public RobotController rc;
	public Team us, them;
	public MapLocation ourHQ, theirHQ;
	public MapLocation here;

	public Bot(RobotController theRC) {
		rc = theRC;
		us = rc.getTeam();
		them = us.opponent();

		ourHQ = rc.senseHQLocation();
		theirHQ = rc.senseEnemyHQLocation();

		FastRandom.init();
		MessageBoard.init(theRC);
		Bfs.init(theRC);
	}

	void updateData() {
		here = rc.getLocation();
	}

	public void loop() throws Exception {
		while (true) {
			// int turn = Clock.getRoundNum();
			try {
				updateData();
				turn();
			} catch (Exception e) {
				e.printStackTrace();
			}
			// if (Clock.getRoundNum() != turn) {
			// System.out.println("!!!!!!!! WENT OVER BYTECODE LIMIT !!!!!!!");
			// throw new Exception("Fix your bytecodes");
			// }
			rc.yield();
		}
	}

	public abstract void turn() throws GameActionException;
}
