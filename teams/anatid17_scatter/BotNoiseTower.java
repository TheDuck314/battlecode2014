package anatid17_scatter;

import battlecode.common.*;

public class BotNoiseTower extends Bot {
	public BotNoiseTower(RobotController theRC) throws GameActionException {
		super(theRC);
		Debug.init(theRC, "pages");
		
		//claim the assignment to build this tower so others know not to build it
		int numPastrLocations = MessageBoard.NUM_PASTR_LOCATIONS.readInt();
		for(int i = 0; i < numPastrLocations; i++) {
			MapLocation pastrLoc = MessageBoard.BEST_PASTR_LOCATIONS.readFromMapLocationList(i);
			if(rc.getLocation().isAdjacentTo(pastrLoc)) {
				MessageBoard.TOWER_BUILDER_ROBOT_IDS.claimAssignment(i);
				break;
			}
		}
	}

	MapLocation pastr = null;

	public void turn() throws GameActionException {
		if (!rc.isActive()) return;

		pastr = findNearestAlliedPastr();
		if (pastr == null || here.distanceSquaredTo(pastr) > RobotType.NOISETOWER.attackRadiusMaxSquared) pastr = here;

		herdTowardPastrDumb();
	}

	private MapLocation findNearestAlliedPastr() {
		MapLocation[] pastrs = rc.sensePastrLocations(us);
		return Util.closest(pastrs, here);
	}

	static final int maxOrthogonalRadius = (int) Math.sqrt(RobotType.NOISETOWER.attackRadiusMaxSquared);
	static final int maxDiagonalRadius = (int) Math.sqrt(RobotType.NOISETOWER.attackRadiusMaxSquared / 2);
	Direction attackDir = Direction.NORTH;
	int radius = attackDir.isDiagonal() ? maxDiagonalRadius : maxOrthogonalRadius;

	private void herdTowardPastrDumb() throws GameActionException {
		MapLocation target = null;

		do {
			int dx = radius * attackDir.dx;
			int dy = radius * attackDir.dy;
			target = new MapLocation(pastr.x + dx, pastr.y + dy);

			radius--;
			if (radius <= (attackDir.isDiagonal() ? 3 : 5)) {
				attackDir = attackDir.rotateRight();
				radius = attackDir.isDiagonal() ? maxDiagonalRadius : maxOrthogonalRadius;
			}
		} while (tooFarOffMap(target) || !rc.canAttackSquare(target));

		rc.attackSquare(target);
	}

	boolean tooFarOffMap(MapLocation loc) {
		int W = 2;
		return loc.x < -W || loc.y < -W || loc.x >= rc.getMapWidth() + W || loc.y >= rc.getMapWidth() + W;
	}

	int herdTargetIndex = 0;
	int smartHerdMaxPoints = 400;

	void herdTowardPastrSmart() throws GameActionException {
		MapLocation attackTarget;
		int numHerdPoints = HerdPattern.readNumHerdPoints(rc);
		do {
			if (herdTargetIndex <= 0) {
				herdTargetIndex = Math.min(smartHerdMaxPoints, numHerdPoints - 1);
				smartHerdMaxPoints += 100;
			}

			// MapLocation herdTarget = herdPoints[herdTargetIndex];
			// Direction pointHerdDir = squareHerdDirs[herdTarget.x][herdTarget.y];
			MapLocation herdTarget = HerdPattern.readHerdMapLocation(herdTargetIndex, rc);
			Direction pointHerdDir = HerdPattern.readHerdDir(herdTargetIndex, rc);

			// attackTarget = herdTarget.add(Util.opposite(pointHerdDir), pointHerdDir.isDiagonal() ? 1 : 2);
			attackTarget = herdTarget.add(Util.opposite(pointHerdDir), pointHerdDir.isDiagonal() ? 2 : 3);

			int skip = herdTargetIndex < 100 ? FastRandom.randInt(1, 3) : FastRandom.randInt(1, 7);
			for (int i = skip; i-- > 0;) {
				herdTargetIndex--;
				MapLocation nextHerdTarget = HerdPattern.readHerdMapLocation(herdTargetIndex, rc);
				if (nextHerdTarget.x == 0 || nextHerdTarget.y == 0 || nextHerdTarget.x == rc.getMapWidth() - 1 || nextHerdTarget.y == rc.getMapHeight() - 1) break;
			}

			// if(herdTargetIndex < 50) herdTargetIndex -= FastRandom.randInt(1, 3);
			// else herdTargetIndex -= FastRandom.randInt(1, 7);
			// Debug.indicate("herd", 0, "" + herdTargetIndex);
			// herdTargetIndex -= 1 + (int)Math.sqrt(herdTargetIndex/2);
		} while (!rc.canAttackSquare(attackTarget));

		// rc.attackSquareLight(attackTarget);
		rc.attackSquare(attackTarget);
	}
}
