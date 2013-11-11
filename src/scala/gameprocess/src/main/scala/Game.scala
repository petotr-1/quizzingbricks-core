import akka.actor._
import scala.collection.mutable.HashMap
import scala.concurrent.duration._

class Game (id: Int, playerIds: Array[Int]) extends Actor
{
    val sideLength = 8
    var board: Array[Int] = null
    var playerMap: HashMap[Int, Player] = null
    var playerList: List[Player] = null
    
    override def preStart()
    {
        board = new Array[Int](sideLength*sideLength)
        for (i <- 0 to board.length-1)
            board(i) = 0
            
        playerMap = new HashMap[Int, Player]()
        for (p <- playerIds)
            playerMap.put(p, new Player(p, Player.PLACING))
            
        playerList = (playerIds map (id => playerMap(id))).toList
    }
    
    def playerToMessage (p: Player) : PlayerMessage =
    {
        PlayerMessage(p.userId, p.state, p.x, p.y, QuestionMessage(p.question.question, p.question.alternatives), p.answer == p.question.correctAnswer && p.state == Player.ANSWERED)
    }
    
    def isValidMove(x: Int, y: Int) = x >= 0 && x < sideLength && y >= 0 && y < sideLength && board(y*sideLength + x) == 0
    
    def getGameInfo() = GameInfoResponse(id, playerList map playerToMessage, board)
    
    def partitionSeveral[A](l: List[A], f: ((A,A) => Boolean)) : List[List[A]] =
        l match
        {
            case Nil =>   Nil
            case x::xs => val (a,b) = l partition (f (x,_))
                          a :: partitionSeveral(b, f)
        }
    
    def handlePlayerMove(player: Int, x: Int, y: Int)
    {
        if(!isValidMove(x, y))
        {
            sender ! GameError("The move is not allowed", 250, GameInfoResponse(id, playerList map playerToMessage, board))
            return
        }
        
        playerMap(player).state = Player.PLACED
        playerMap(player).x = x
        playerMap(player).y = y
        sender ! GameInfoResponse(id, playerList map playerToMessage, board)
    }
    
    def questionTimeout(player: Int)
    {
        playerMap(player).state match
        {
            case Player.ANSWERING =>
                println("Player " + player + " timed out to answer question.") 
                self ! handleAnswer(id, player, 0)
            case _ =>
                println("Question timeout while player not in AnsweringQuestion state!")
        }
    }
    
    def handleQuestionRequest(player: Int)
    {
        playerMap(player).state match
        {
            case Player.PLACED =>
                import context.dispatcher
                val pendingTimeout = context.system.scheduler.scheduleOnce(200 seconds, self, ("timeout", player))
                val question: Question = QuestionDatabase.getQuestion
                playerMap(player).state = Player.ANSWERING
                playerMap(player).pendingTimeout = pendingTimeout
                playerMap(player).question = question
                sender ! QuestionResponse(question.question, question.alternatives)
                
            case _ =>
                sender ! GameError("Question is not allowed in the present state", 300, getGameInfo)
        }
    }
    
    def handleGameInfoResponse(idReq: Int)
    {
        assert(idReq == id)
        sender ! GameInfoResponse(id, playerList map playerToMessage, board)
    }
    
    def handleAnswer(id: Int, player: Int, answer: Int)
    {
        playerMap(player).state match
        {
            case Player.ANSWERING =>
                playerMap(player).pendingTimeout.cancel()
                playerMap(player).state = Player.ANSWERED
                playerMap(player).answer = answer
                if(answer != 0)
                    sender ! AnswerResponse(playerMap(player).question.correctAnswer == answer)
                
                if(!playerList.forall(p => p.state >= Player.PLACED))
                    return
                
                val sites = partitionSeveral(playerList, (p1: Player, p2: Player) => p1.x == p2.x && p1.y == p2.y)
                for(site <- sites)
                {
                    println("site")
                    site.foreach(p => println(p.userId))
                    val correctAnswers = site.filter(p => p.state == Player.ANSWERED && p.answer == p.question.correctAnswer)
                    if(correctAnswers.length > 1)
                        for(c <- correctAnswers)
                            c.state = Player.PLACED
                }
                
                if(!playerList.forall(p => p.state == Player.ANSWERED))
                    return
                    
                for(p <- playerList)
                {
                    if(p.answer == p.question.correctAnswer)
                        board(sideLength*p.y + p.x) = p.userId
                    p.reset()
                }
            case _ =>
                sender ! GameError("The answer was too late", 252, getGameInfo)
        }
    }
    
    def receive =
    {
        case m: PlayerRequestMessage =>
            if (!(playerList map (p => p.userId) contains m.userId))
                sender ! GameError("You are not permitted to that game", 251, GameInfoResponse(id, playerList map playerToMessage, board))
            m match 
            {
                case PlayerMove(_, player, x, y) =>
                    handlePlayerMove(player, x, y)
                case QuestionRequest(_, player) =>
                    handleQuestionRequest(player)
                case Answer(id, playerId, ans) =>
                    if(ans < 1 || ans > 4)
                        sender ! GameError("Incorrect answer alternative given", 300, getGameInfo())
                    handleAnswer(id, playerId, ans)
            }
        
        case ("timeout", player: Int) =>
            questionTimeout(player)
        case GameInfoRequest(idReq) => 
            handleGameInfoResponse(idReq)
    }
}