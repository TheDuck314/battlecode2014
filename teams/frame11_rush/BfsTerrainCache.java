package frame11_rush;

import battlecode.common.*;

public class BfsTerrainCache {
	private static TerrainTile[][] terrain = new TerrainTile[GameConstants.MAP_MAX_WIDTH][GameConstants.MAP_MAX_HEIGHT];
	public static boolean cacheFinished = false;

	private static int workLocX = 0, workLocY = 0;
	private static boolean workStarted = false;

	public static void workOnCache(RobotController rc, int bytecodeLimit) {
		if(cacheFinished) return;
		
		int mapWidth = rc.getMapWidth();
		int mapHeight = rc.getMapHeight();

		int x, y;
		if (workStarted) {
			x = workLocX;
			y = workLocY;
		} else {
			x = mapWidth - 1;
			y = mapHeight - 1;
			workStarted = true;
		}

		outerloop: while (x >= 0) {
			while (y >= 0) {
				terrain[x][y] = rc.senseTerrainTile(new MapLocation(x, y));
				y--;
				//TODO: this is in a tight loop so it would be nice to do this check less often
				if (Clock.getBytecodeNum() > bytecodeLimit) break outerloop;
			}
			y = mapHeight - 1;
			x--;
		}

		workLocX = x;
		workLocY = y;

		if(x < 0) cacheFinished = true;
	}
	/*
	 * public static void createCache(RobotController rc) { Util.timerStart(); int mapWidth = rc.getMapWidth(); int mapHeight = rc.getMapHeight(); for(int x =
	 * mapWidth; x --> 0; ) { for(int y = mapHeight; y --> 0; ) { terrain[x][y] = rc.senseTerrainTile(new MapLocation(x, y)); } } int roundEnd =
	 * Clock.getRoundNum(); Util.timerEnd("createCache"); }
	 */
}
