import java.math.BigInteger
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.Executors

import mail._
import org.json.JSONObject
import akka.actor.Actor
import org.mongodb.scala._
import spray.json._
import spray.routing.HttpService
import spray.routing.authentication.{BasicHttpAuthenticator, UserPassAuthenticator}
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import javax.mail.internet.InternetAddress
import javax.xml.bind.DatatypeConverter

import com.mongodb.BasicDBList
import com.mongodb.casbah.Imports
import com.mongodb.casbah.commons.MongoDBObject
import main.KasbahMongoDriver
import main.models.MyJsonProtocol._
import main.models.{Company, User}
import org.apache.commons.codec.binary.Base64
import org.bson.types.ObjectId
import com.mongodb.casbah.query.Imports._
import org.apache.http.impl.client.BasicResponseHandler

import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random
import scala.util.parsing.json.JSONArray
class SampleServiceActor extends Actor with SampleRoute {
  def actorRefFactory = context
  def receive = runRoute(route)
}

trait SampleRoute extends HttpService {
  import spray.httpx.SprayJsonSupport._
  import spray.http.MediaTypes
  import main.BearerTokenGenerator

  val key: String = "7AA38A614149293C80FFD1EB95FD0225"
  val tokensMap:List[String] = List()
  val tokens = scala.collection.mutable.Map[String, Long]()

  def getExpiration(id:String):Long = {
    return tokens.get(id).get
  }

  def sendPostRequest(jsonBody : String)= {
    import org.apache.http.client.methods.HttpPost
    import org.apache.http.entity.StringEntity
    import org.apache.http.impl.client.DefaultHttpClient
    import com.google.gson.Gson
    // create a Stock object
//    val stock = new Stock("AAPL", 650.00)

    // convert it to a JSON string
    // create an HttpPost object
    val post = new HttpPost("https://fcm.googleapis.com/fcm/send")

    // set the Content-type
    post.setHeader("Content-Type", "application/json")
    post.setHeader("Authorization","key=AIzaSyBq6kVVAgW5205RDhYDz4bdCqWq9f1G9sI")
    // add the JSON as a StringEntity
    post.setEntity(new StringEntity(jsonBody))
    // send the post request
    implicit val context = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
    val postThread = Future{
      val response = (new DefaultHttpClient).execute(post)
      println("response = "+ response)
    }


    //    val responseJSON = new JSONObject(new BasicResponseHandler().handleResponse(response))
//    val responseString = responseJSON.get("notification_key").asInstanceOf[String]
//
//    // print the response headers
//    println("notification_key: " + responseString)
//    return responseString
  }

  class FireBaseRequest(var operation: String, var notification_key_name: String,
                        var notification_key : String, var registration_ids : String) {
    override def toString = operation + ", " + notification_key_name + ", " + notification_key + ", " + registration_ids
  }
  def generateAddToken():String = {
    val seconds = Calendar.getInstance().getTimeInMillis
    val bearer:BearerTokenGenerator = new BearerTokenGenerator
    val token = bearer.generateMD5Token("testprefix")
    val expiration = seconds + 1800000
    tokens += token -> expiration
    return token
  }
  val base64 = "data:([a-z]+);base64,(.*)".r

  object Encryption {
    def encrypt(key: String, value: String): String = {
      val cipher: Cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
      cipher.init(Cipher.ENCRYPT_MODE, keyToSpec(key))
      Base64.encodeBase64String(cipher.doFinal(value.getBytes("UTF-8")))
    }

    def decrypt(key: String, encryptedValue: String): String = {
      val cipher: Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
      var iv = new Array[Byte](16)
      Random.nextBytes(iv)
      val specIv = new IvParameterSpec(iv)
      cipher.init(Cipher.DECRYPT_MODE, keyToSpec(key),specIv)
      new String(cipher.doFinal(Base64.decodeBase64(encryptedValue)))
    }
    def keyToSpec(key: String): SecretKeySpec = {
      var keyBytes: Array[Byte] = (key).getBytes("UTF-8")
      val sha: MessageDigest = MessageDigest.getInstance("SHA-1")
      keyBytes = sha.digest(keyBytes)
      keyBytes = java.util.Arrays.copyOf(keyBytes, 16)
      new SecretKeySpec(keyBytes, "AES")
    }
  }
  def sendMail(to: String, subject: String, message: String) = {
    import courier._, Defaults._
    val mailer = Mailer("smtp.gmail.com", 587)
      .auth(true)
      .as("hey.youssef@hey-labs.com", "St44ElYos3f")
      .startTtls(true)()
    val fromAddress:InternetAddress = new InternetAddress("heylist@heylabs.com")
    val toAddress:InternetAddress = new InternetAddress(to)

    mailer(Envelope.from(fromAddress)
      .to(toAddress)
      .subject(subject)
      .content(Multipart()
        .html(message)))
      .onSuccess {
        case _ => println("delivered report")
      }
  }
  def sendFirebaseNotification(jsonList: JSONObject, action: String) = {
    val jsonCreateGroupeFirebaseBody:JSONObject = new JSONObject()
    jsonCreateGroupeFirebaseBody.put("to","/topics/"+jsonList.getString("listId"))
    val msg = new JSONObject()
    val msgData = new JSONObject()
    msgData.put("action",action)
    msgData.put("item",jsonList.getString("item"))
    msgData.put("username",jsonList.getString("username"))
    msg.put("message",msgData)
    //                msg.put("userId",jsonList.getString("userId"))
    jsonCreateGroupeFirebaseBody.put("data", msg)
    println("jsonfirebase" + jsonCreateGroupeFirebaseBody)
    sendPostRequest(jsonCreateGroupeFirebaseBody.toString())
  }

  val mongoDriver = new KasbahMongoDriver
  val resultJson:String = ""
  val route = {
    path("auth"){
      respondWithMediaType(MediaTypes.`application/json`) {
        post {
          entity(as[User]) { user =>
            val pwd: String = user.password

            //            user.password = Base64.decodeBase64()
            var hasOrg = false
            val query = Document("username" -> user.username)
            val userDBCriteria = MongoDBObject("username" -> user.username)
            var userDB: Option[User] = None
            val jsonResponse: JSONObject = new JSONObject()
            mongoDriver.usersCollection.findOne(userDBCriteria).foreach { x =>
              val id = x.get("_id").asInstanceOf[ObjectId].toString
              val username = x.get("username").asInstanceOf[String]
              val pwd = x.get("password").asInstanceOf[String]
              val email_address = x.get("email_address").asInstanceOf[String]
              var orgId = ""
              if (x.containsField("orgId")) {
                orgId = x.get("orgId").asInstanceOf[String]
                hasOrg = true
              }
              val cUser = User(Some(id), username, pwd,email_address, Some(orgId))
              userDB = Some(cUser)

            }
            if(userDB==None)
              complete("none")
            //            println("userdbpwd = " + userDB.get.password)
            //            println("userpwd = " + user.password)
            //            val pwdMongo = String(BCrypt.decode_base64(userDB.get.password,10000)
            else if (BCrypt.checkpw(user.password, userDB.get.password)) {
              val token = generateAddToken()
              if(hasOrg) {
                val company = mongoDriver.orgsCollection.findOne(MongoDBObject("_id" -> new ObjectId(userDB.get.orgID.get)))
                jsonResponse.put("company", company.get.toString)
              }
              jsonResponse.put("user", userDB.toJson.toString())
              jsonResponse.put("token", generateAddToken())

              //                val jsonUser = userDB.toJson
              //                if(userDB.get.orgID.equals(""))
              //                  complete(jsonResponse.toString())
              //                else{
              //                  var company:Company = Company(None,"","",None)
              //                  val companyDBCriteria = MongoDBObject("orgId"->userDB.get.orgID)
              //                  mongoDriver.orgsCollection.findOne(companyDBCriteria).foreach { x =>
              //                    val companyName = x.get("name").asInstanceOf[String]
              //                    val companyId = x.get("_id").asInstanceOf[ObjectId].toString()
              //                    val listId = x.get("listId").asInstanceOf[String]
              //                    val creatorId = x.get("creatorId").asInstanceOf[String]
              //                    company._id=Some(companyId)
              //                    company.ListId=Some(listId)
              //                    company.creatorId=creatorId
              //                    company.name=companyName
              //                  }
              //                  import main.models.CompanyJsonProtocol._
              //                  jsonResponse.put("company",company.toJson.toString())
              complete(jsonResponse.toString())

            }
            else
              complete("fail")
          }
        }
      }
    } ~
    path("newgroup"){
      import main.models.CompanyJsonProtocol._
      respondWithMediaType(MediaTypes.`application/json`) {
        post{
          entity(as[Company]){ company =>
            val companyDBCriteria = MongoDBObject("name"->company.name)
            var companyDB: Option[Company] = None
            mongoDriver.orgsCollection.findOne(companyDBCriteria).foreach{ x =>
              val id = x.get("_id").asInstanceOf[ObjectId].toString
              val name = x.get("name").asInstanceOf[String]
              val listId = x.get("listId").asInstanceOf[String]
              val creator = x.get("creatorId").asInstanceOf[String]
              val company = Company(Some(id),name, creator, Some(listId))
              companyDB = Some(company)
            }
            if(companyDB != None)
              complete("exists")
            else{

              val json:JSONObject = new JSONObject()
              mongoDriver.orgsCollection += MongoDBObject("name" -> company.name,"creatorId" -> company.creatorId)
              mongoDriver.orgsCollection.findOne(companyDBCriteria).foreach { x =>
                var array = new ListBuffer[String]()
//                array += "Sample/Exemple"
//                array += "oranges"
//                array += "test"
                val list = mongoDriver.listCollection += MongoDBObject("orgId" -> x.get("_id").asInstanceOf[ObjectId].toString, "items"-> array)
//                mongoDriver.orgsCollection += MongoDBObject("name" -> company.name,"creatorId" -> company.creatorID)
                mongoDriver.listCollection.findOne(MongoDBObject("orgId"->x.get("_id").asInstanceOf[ObjectId].toString)).foreach { y =>
                  val update = MongoDBObject("$set" -> MongoDBObject("listId"->y.get("_id").asInstanceOf[ObjectId].toString()))
                  val q1 = MongoDBObject("_id"->x.get("_id"))
                  val companyDB = mongoDriver.orgsCollection.findAndModify(q1,update)
                  var arraySet = new ListBuffer[Imports.DBObject]()
                  val dbo = new MongoDBObject()
                  dbo.put("orgId",x.get("_id").asInstanceOf[ObjectId].toString)
                  dbo.put("listId",y.get("_id").asInstanceOf[ObjectId].toString())
                  println("listid = "+dbo.get("listId").get)
                  val jsonCreateGroupeFirebaseBody:JSONObject = new JSONObject()
                  jsonCreateGroupeFirebaseBody.put("to","/topics/"+dbo.get("listId"))
                  jsonCreateGroupeFirebaseBody.put("data", "creation")
                  sendPostRequest(jsonCreateGroupeFirebaseBody.toString())
                  val q2 = MongoDBObject("_id"-> new ObjectId(x.get("creatorId").asInstanceOf[String]))
                  val updateUser = MongoDBObject("$set" -> dbo)

                  val userDB = mongoDriver.usersCollection.findAndModify(q2,updateUser)
//                  println(userDB)
                  val companyDBC = Company(Some(x.get("_id").asInstanceOf[ObjectId].toString),x.get("name").asInstanceOf[String],company.creatorId,Some(y.get("_id").asInstanceOf[ObjectId].toString()))
                  val userDBC = User(Some(company.creatorId),userDB.get.get("username").asInstanceOf[String],"",userDB.get("email_address").asInstanceOf[String],Some(x.get("_id").asInstanceOf[ObjectId].toString))
                  json.put("company",companyDBC.toJson.toString)
                  json.put("user",userDBC.toJson.toString)
                  json.put("token",generateAddToken())
//                  json.
//                  complete(y.get("_id").asInstanceOf[ObjectId].toString() + generateAddToken())
                }
              }

//              val token = generateAddToken()
//              complete(token)
              complete(json.toString())

            }

          }
        }
      }
    } ~
    path("register"){
      respondWithMediaType(MediaTypes.`application/json`) {
        post {
          entity(as[User]) { user =>
            val userDBCriteria = MongoDBObject("username"->user.username)
            var userDB: Option[User] = None
            mongoDriver.usersCollection.findOne(userDBCriteria).foreach{ x =>
              val id = x.get("_id").asInstanceOf[ObjectId].toString
              val username = x.get("username").asInstanceOf[String]
              val pwd = x.get("password").asInstanceOf[String]
              val pwdHashed = BCrypt.hashpw(pwd,BCrypt.gensalt())
              val email_address = x.get("email_address").asInstanceOf[String]
              println(pwdHashed)
                val cUser = User(Some(id), username, pwdHashed, email_address)
                userDB = Some(cUser)
            }
            if(userDB != None)
              complete("exists")
            else{
              val json:JSONObject = new JSONObject()
              userDB = Some(user)
              implicit val context = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())
              val mail = Future{
                val message = "Thank you for using Hey!List, this is a reminder of your login informations: " +
                  "<br /><br /><strong><u> Please keep this e-mail, this is the only time your password is visible, it is now secured and no one will ever have access to it again except you</u></strong>"+
                  "<br /><br /><strong>Username: </strong>" + userDB.get.username +
                  "<br /><strong>Password: </strong>" + userDB.get.password +
                  "<br /><br /><a href=\"https://play.google.com/store/apps/details?id=com.hey.youssef.hey.list\"><strong>Click here to download Hey!List now</strong> </a>"
                sendMail(userDB.get.email_address,"Hey!List: Your Login Informations",message)
              }
              val pwdHashed = BCrypt.hashpw(userDB.get.password,BCrypt.gensalt())
              mongoDriver.usersCollection += MongoDBObject("username" -> userDB.get.username,"password" -> pwdHashed,"email_address" -> userDB.get.email_address)
              mongoDriver.usersCollection.findOne(userDBCriteria).foreach{ x =>
                val newUser = User(Some(x.get("_id").asInstanceOf[ObjectId].toString()),
                  x.get("username").asInstanceOf[String],"",x.get("email_address").asInstanceOf[String])
                json.put("user",newUser.toJson.toString())
              }
              complete(json.toString())
            }

          }

        }
        }
    } ~
      path("addUserGroup") {
        respondWithMediaType(MediaTypes.`application/json`) {
          post {
            entity(as[String]) { jsonStr =>
              val jsonList = new JSONObject(jsonStr)
              headerValueByName("authToken") { token =>
                if (tokens.contains(token)) {
                  val listDB = mongoDriver.usersCollection.update(MongoDBObject("username" -> jsonList.getString("userName")), $set("orgId" -> jsonList.getString("orgId").toString(),"listId" -> jsonList.getString("listId")))
                  val jsonResult: JSONObject = new JSONObject()
                  jsonResult.put("result","ok")

                  //                  val query = MongoDBObject("_id" -> new ObjectId(jsonList.getString("listId")))
//                  var results = ""
//                  val json = mongoDriver.listCollection.findOneByID( new ObjectId(jsonList.getString("listId")))
//                  println(json)
//                  jsonResult.put("list", json.get("items").asInstanceOf[BasicDBList].toString)

                  if (tokens.get(token).get < Calendar.getInstance().getTimeInMillis) {
                    tokens.remove(token)
                    val newToken = generateAddToken()
                    jsonResult.put("token", newToken)
                  }
                  else {
                    tokens.remove(token)
                    val expiration = Calendar.getInstance().getTimeInMillis + 180000
                    tokens += token -> expiration
                  }
                  complete(jsonResult.toString())
                }
                else
                  complete("invalidToken")
              }
            }
          }
        }
      }~
    path("disconnect"){
      post{
        entity(as[String]){token =>
          tokens.remove(token)
        complete("disconnected")
        }
      }
    } ~
    path("addItem") {
      respondWithMediaType(MediaTypes.`application/json`) {
        post {
          entity(as[String]) { jsonStr =>
            val jsonList = new JSONObject(jsonStr)
            headerValueByName("authToken") { token =>
              if (tokens.contains(token)) {
                val item = MongoDBObject.newBuilder
                item += "name" -> jsonList.getString("item")
                item += "checked" -> false
                val listDB = mongoDriver.listCollection.update(MongoDBObject("_id" -> new ObjectId(jsonList.getString("listId"))), $addToSet("items" -> item.result()))

                val query = MongoDBObject("_id" -> new ObjectId(jsonList.getString("listId")))
                var results = ""
                val jsonResult: JSONObject = new JSONObject()
                val json = mongoDriver.listCollection.findOneByID( new ObjectId(jsonList.getString("listId")))
                println(json)
                jsonResult.put("list", json.get("items").asInstanceOf[BasicDBList].toString)

                if (tokens.get(token).get < Calendar.getInstance().getTimeInMillis) {
                  tokens.remove(token)
                  val newToken = generateAddToken()
                  jsonResult.put("token", newToken)
                }
                else {
                  tokens.remove(token)
                  val expiration = Calendar.getInstance().getTimeInMillis + 180000
                  tokens += token -> expiration
                }
                sendFirebaseNotification(jsonList,"add")
                complete(jsonResult.toString())
              }
              else
                complete("invalidToken")
            }
          }
        }
      }
    } ~
      path("checkItem") {
        respondWithMediaType(MediaTypes.`application/json`) {
          post {
            entity(as[String]) { jsonStr =>
              val jsonList = new JSONObject(jsonStr)
              headerValueByName("authToken") { token =>
                if (tokens.contains(token)) {
                  val item = MongoDBObject.newBuilder
                  item += "_id" -> new ObjectId(jsonList.getString("listId"))
                  item += "items.name" -> jsonList.getString("item")
                  val checked = jsonList.getString("checked").toBoolean
                  val listDB = mongoDriver.listCollection.update(item.result(), $set("items.$.checked" -> checked))

                  val query = MongoDBObject("_id" -> new ObjectId(jsonList.getString("listId")))
                  var results = ""
                  val jsonResult: JSONObject = new JSONObject()
                  val json = mongoDriver.listCollection.findOneByID( new ObjectId(jsonList.getString("listId")))
                  println(json)
                  jsonResult.put("list", json.get("items").asInstanceOf[BasicDBList].toString)

                  if (tokens.get(token).get < Calendar.getInstance().getTimeInMillis) {
                    tokens.remove(token)
                    val newToken = generateAddToken()
                    jsonResult.put("token", newToken)
                  }
                  else {
                    tokens.remove(token)
                    val expiration = Calendar.getInstance().getTimeInMillis + 180000
                    tokens += token -> expiration
                  }
                  if(checked.equals(true))
                    sendFirebaseNotification(jsonList,"check")
                  else
                    sendFirebaseNotification(jsonList,"uncheck")
                  complete(jsonResult.toString())
                }
                else
                  complete("invalidToken")
              }
            }
          }
        }
      }~
        path("removeItem") {
          respondWithMediaType(MediaTypes.`application/json`) {
            post {
              entity(as[String]) { jsonStr =>
                val jsonList = new JSONObject(jsonStr)
                headerValueByName("authToken") { token =>
                  if (tokens.contains(token)) {
                    val item = MongoDBObject.newBuilder
                    item += "_id" -> new ObjectId(jsonList.getString("listId"))
                    //                  item += "items.name" -> jsonList.getString("item")
                    val elemMatch = MongoDBObject("name"->jsonList.getString("item"))
                    val listDB = mongoDriver.listCollection.update(item.result(), $pull("items"-> elemMatch.result()))

                    val query = MongoDBObject("_id" -> new ObjectId(jsonList.getString("listId")))
                    var results = ""

                    val jsonResult: JSONObject = new JSONObject()
                    val json = mongoDriver.listCollection.findOneByID( new ObjectId(jsonList.getString("listId")))
                    println(json)
                    jsonResult.put("list", json.get("items").asInstanceOf[BasicDBList].toString)

                    if (tokens.get(token).get < Calendar.getInstance().getTimeInMillis) {
                      tokens.remove(token)
                      val newToken = generateAddToken()
                      jsonResult.put("token", newToken)
                    }
                    else {
                      tokens.remove(token)
                      val expiration = Calendar.getInstance().getTimeInMillis + 180000
                      tokens += token -> expiration
                    }
                    sendFirebaseNotification(jsonList,"remove")
                    complete(jsonResult.toString())
                  }
                  else
                    complete("invalidToken")
                }
              }
            }
          }
        } ~
        path("getList"){
          post {
            entity(as[String]) { jsonClientStr =>
              val jsonClient = new JSONObject(jsonClientStr)
              headerValueByName("authToken") { token =>
                if (tokens.contains(token)) {
                  val query = MongoDBObject("_id" -> new ObjectId(jsonClient.getString("listId")))
                  var results = ""
                  val jsonResult: JSONObject = new JSONObject()
                  val json = mongoDriver.listCollection.find(query).foreach { x =>
                    jsonResult.put("list", x.get("items").asInstanceOf[BasicDBList].toString)
                  }
                  if (tokens.get(token).get < Calendar.getInstance().getTimeInMillis) {
                    tokens.remove(token)
                    val newToken = generateAddToken()
                    jsonResult.put("token", newToken)
                  }
                  else {
                    tokens.remove(token)
                    val expiration = Calendar.getInstance().getTimeInMillis + 180000
                    tokens += token -> expiration
                  }
                  complete(jsonResult.toString())
                }
                else
                  complete("invalidToken")
              }
            }
          }
        }
    }
    }


