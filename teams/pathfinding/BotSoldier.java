package pathfinding;

import battlecode.common.*;

public class BotSoldier extends Bot {
	public BotSoldier(RobotController theRC) {
		super(theRC);
		Debug.init(theRC, "pastr");
		Nav.init(theRC);

		chooseInitialState();
	}

	private enum SoldierState {
		MOVING_TO_PASTR_LOC,
	}

	SoldierState state;

	// MOVING_TO_PASTR_LOC
	MapLocation buildPastrLoc;

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;

		Debug.indicate("pastr", 0, "state = " + state.toString());

		switch (state) {
			case MOVING_TO_PASTR_LOC:
				moveToBuildPastrLoc();
				break;
		}
	}

	private void chooseInitialState() {
		state = SoldierState.MOVING_TO_PASTR_LOC;
		buildPastrLoc = new MapLocation(-99, -99);
		while (!Util.passable(rc.senseTerrainTile(buildPastrLoc))) {
			buildPastrLoc = new MapLocation(ourHQ.x + FastRandom.randInt(-rc.getMapWidth() / 2, rc.getMapWidth() / 2), ourHQ.y
					+ FastRandom.randInt(-rc.getMapHeight() / 2, rc.getMapHeight() / 2));
		}
	}

	private void moveToBuildPastrLoc() throws GameActionException {
		Debug.indicate("pastr", 1, "buildPastrLoc = " + buildPastrLoc.toString());
		if (here.equals(buildPastrLoc)) {
			rc.construct(RobotType.PASTR);
		} else {
			Nav.goTo(buildPastrLoc);
		}
	}
}
