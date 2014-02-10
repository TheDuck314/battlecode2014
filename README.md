This is the code for my Battlecode 2014 entry as "that one team." The final bot is in teams/zephyr and won the final tournament.

#### Highlights

**Pathfinding:** I used a combination of bug and breadth-first search to good effect. Robots navigated to rally points with bug while the HQ ran a BFS from the destination and broadcasted its progress each turn. Robots switched to using the BFS results whenever they reached the growing map region covered by the BFS. Bug is implemented in Nav.java. The BFS is implemented in Bfs.java and its results are used in Nav.java.

**Combat micro:** I wasn't too happy with the structure of my combat micro code, but it seemed to be effective. One thing that I was pretty happy with was my implementation of self-destructs. My combat micro is the last thousand or so lines of BotSoldier.java.

**Strategy:** The high-level strategic decisions were made by the HQ and implemented in BotHQ.java. I implemented a bunch of different strategies, listed in Strategy.java, but in my final bot I only ended up using RUSH or SCATTER_HALF depending on the map. 

**Debugging:** Debug.java has some useful wrappers around the API's indicator string and bytecode-counting functions  (the body of the Debug class is commented out in my final bot). The most useful feature is the ability to activate only a certain set of indicator string calls, as specified by a string like "micro" or "nav". This let me leave lots of indicator string calls around in my dev versions and quickly switch which ones I wanted to actually see displayed. I got this idea from [fun gamers' 2012 strategy report](http://cdn.bitbucket.org/Cixelyn/bcode2012-bot/downloads/strategyreport.pdf) -- they used it to prevent conflicts between different team members' indicator strings.

#### Non-final versions

My sprint tournament submission is "sprint" and my seeding tournament submission is "seeding_scatter_half" (it was going to be "seeding" until I decided to make an adjustment to my strategy a few hours before the deadline). The teams folder is littered with other bots I used for testing. Over the course of January I used a sequence of three codenames to name these bots: first "framework," then "anatid," and finally "zephyr". Most bot names also have numbers, which are the dates in January on which I added them. 

#### Other Battlecode 2014 repos

A few other teams have published their bots:

* [PaddleGoats](https://github.com/u--/PaddleGoats2014), a finalist
* [anim0rphs](https://bitbucket.org/jdshen/battlecode-2014), a finalist
* [0xg](https://github.com/0xg/Battlecode-2014), a strong team that almost qualified for the final tournament
* [@Deprecated](http://math.columbia.edu/~dgulotta/bc2014.jar), a strong team that was ineligible for the final tournament
* [TooMuchSwagException](https://bitbucket.org/Goldob/battlecode2014)
* [FireAnts](https://bitbucket.org/schueppert/battlecode2014/)
* [CATTLEBODE](https://github.com/optimuscoprime/battlecode2014)
* [Mothership rush](https://dl.dropboxusercontent.com/u/9059/team197.jar)
