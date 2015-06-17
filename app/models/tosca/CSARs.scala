/* 
** Copyright [2013-2014] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package models.tosca

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.EitherT._
import scalaz.Validation
//import scalaz.Validation.FlatMap._
import scalaz.NonEmptyList._
import scalaz.syntax.SemigroupOps
import org.megam.util.Time
import controllers.stack._
import controllers.Constants._
import controllers.funnel.FunnelErrors._
import models._
import models.cache._
import models.riak._
import com.stackmob.scaliak._
import com.basho.riak.client.core.query.indexes.{ RiakIndexes, StringBinIndex, LongIntIndex }
import com.basho.riak.client.core.util.{ Constants => RiakConstants }
import org.megam.common.riak.{ GSRiak, GunnySack }
import org.megam.common.uid.UID
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.nio.charset.Charset
import org.megam.common.amqp.response._

/**
 * @author rajthilak
 *
 */
case class CSARInput(doc: String)

case class CSARResult(id: String, desc: String, link: String, created_at: String) {

  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    import models.json.tosca.CSARResultSerialization
    val preser = new CSARResultSerialization()
    toJSON(this)(preser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    pretty(render(toJValue))
  } else {
    compactRender(toJValue)
  }
}

object CSARResult {

  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[CSARResult] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    import models.json.tosca.CSARResultSerialization
    val preser = new CSARResultSerialization()
    fromJSON(jValue)(preser.reader)
  }

  def fromJson(json: String): Result[CSARResult] = (Validation.fromTryCatch[net.liftweb.json.JValue] {
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

  val empty = CSARResult("", "", "", "")

}

object CSARs {

  implicit val formats = DefaultFormats
  private val riak = GWRiak("csars")
  implicit def CSARsSemigroup: Semigroup[CSARResults] = Semigroup.instance((f1, f2) => f1.append(f2))

  val metadataKey = "CSAR"
  val metadataVal = "CSARs Creation"
  val bindex = "csar"

  /**
   * A private method which chains computation to make GunnySack when provided with an input json, email.
   * parses the json, and converts it to nodeinput, if there is an error during parsing, a MalformedBodyError is sent back.
   * After that flatMap on its success and the account id information is looked up.
   * If the account id is looked up successfully, then yield the GunnySack object.
   */
  private def mkGunnySack(email: String, input: String): ValidationNel[Throwable, Option[GunnySack]] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.CSARs", "mkGunnySack:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    play.api.Logger.debug(("%-20s -->[%s]").format("json", input))

    for {
      aor <- (Accounts.findByEmail(email) leftMap { t: NonEmptyList[Throwable] => t })
      csir <- (CSARLinks.create(email, input) leftMap { err: NonEmptyList[Throwable] => err })
      uir <- (UID(MConfig.snowflakeHost, MConfig.snowflakePort, "csr").get leftMap { ut: NonEmptyList[Throwable] => ut })
    } yield {
      val csar_res = new CSARResult(uir.get._1 + uir.get._2, csir.desc, csir.id, Time.now.toString)
      val bvalue = Set(aor.get.id)
      play.api.Logger.debug(("%-20s -->[%s]").format("tosca.CSARs", "mkGunnysack" + bvalue))
      new GunnySack(csar_res.id, csar_res.toJson(false), RiakConstants.CTYPE_TEXT_UTF8, None,
        Map(metadataKey -> metadataVal), Map((bindex, bvalue))).some
    }
  }

  /*
   * create new market place item with the 'name' of the item provide as input.
   * A index name csar name will point to the "csars" bucket
   */
  def create(email: String, input: String): ValidationNel[Throwable, Option[CSARResult]] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.CSARs", "create:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))

    (mkGunnySack(email, input) leftMap { err: NonEmptyList[Throwable] =>
      new ServiceUnavailableError(input, (err.list.map(m => m.getMessage)).mkString("\n"))
    }).toValidationNel.flatMap { gs: Option[GunnySack] =>

      (riak.store(gs.get) leftMap { t: NonEmptyList[Throwable] => t }).
        flatMap { maybeGS: Option[GunnySack] =>
          maybeGS match {
            case Some(thatGS) => (parse(thatGS.value).extract[CSARResult].some).successNel[Throwable]
            case None => {
              play.api.Logger.warn(("%-20s -->[%s]").format("CSAR.created success", "Scaliak returned => None. Thats OK."))
              (parse(gs.get.value).extract[CSARResult].some).successNel[Throwable]
            }
          }
        }
    }
  }

  def getCSAR(input: String): ValidationNel[Throwable, CSARResults] = {
    for {
      csir <- (CSARs.findByName(List(input).some) leftMap { err: NonEmptyList[Throwable] => err })
    } yield {
      csir
    }
  }

  def getCsarLink(input: CSARResults): ValidationNel[Throwable, CSARLinkResults] = {
    (input map {
      _.map { csars =>
        InMemory[ValidationNel[Throwable, CSARLinkResults]]({
          csar =>
            {
              play.api.Logger.debug("tosca.CSARs findLinksByName: csars:" + csars)
              CSARLinks.findByName(List(csars.link).some)
            }
        }).get(csars.link).eval(InMemoryCache[ValidationNel[Throwable, CSARLinkResults]]())
      }
    } map {
      _.foldRight((CSARLinkResults.empty).successNel[Throwable])(_ +++ _)
    }).head //return the folded element in the head. 
  }
  
  def getYaml(input: CSARLinkResults): ValidationNel[Throwable, String] = {
    (input map {
      _.map { links =>
        InMemory[ValidationNel[Throwable, String]]({
          csar =>
            {
              play.api.Logger.debug("tosca.CSARs findLinksByName: csars:" + links)
              CSARJson.toJson(links.desc)
            }
        }).get(links.desc).eval(InMemoryCache[ValidationNel[Throwable, String]]())
      }
    } map {
      _.foldRight(("").successNel[Throwable])(_ +++ _)
    }).head //return the folded element in the head. 
  }

  def push(email: String, input: String): ValidationNel[Throwable, AMQPResponse] = {
    for {
      csar <- (CSARs.getCSAR(input) leftMap { err: NonEmptyList[Throwable] => err })
      csir <- (getCsarLink(csar) leftMap { err: NonEmptyList[Throwable] => err })
      json <- (getYaml(csir) leftMap { err: NonEmptyList[Throwable] => err })
      asm <- (Assemblies.create(email, json) leftMap { err: NonEmptyList[Throwable] => err })
      request <- (models.Requests.createforNewNode("{\"node_id\": \"" + asm.get.id + "\",\"node_name\": \"" + asm.get.name + "\",\"req_type\": \"create\"}") leftMap { err: NonEmptyList[Throwable] => err })
      amqp <- (CloudStandUpPublish(request.get._2, request.get._1).dop leftMap { err: NonEmptyList[Throwable] => err })
    } yield {
      play.api.Logger.debug("tosca.CSARs Pushed: csars:" + json)
      amqp
    }
  }
  

  def findLinksByName(csarslinksNameList: Option[List[String]]): ValidationNel[Throwable, CSARLinkResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.CSARs", "findLinksByNodeName:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("csarlinksList", csarslinksNameList))
    (csarslinksNameList map {
      _.map { csarslinksName =>
        InMemory[ValidationNel[Throwable, CSARLinkResults]]({
          clinkname: String =>
            {
              play.api.Logger.debug("tosca.CSARs findLinksByName: csars:" + csarslinksName)
              CSARLinks.findByName(List(clinkname).some)
            }
        }).get(csarslinksName).eval(InMemoryCache[ValidationNel[Throwable, CSARLinkResults]]())
      }
    } map {
      _.foldRight((CSARLinkResults.empty).successNel[Throwable])(_ +++ _)
    }).head //return the folded element in the head. 
  }

  def findByName(csarsNameList: Option[List[String]]): ValidationNel[Throwable, CSARResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.CSARs", "findByNodeName:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("csarList", csarsNameList))
    (csarsNameList map {
      _.map { csarsName =>
        InMemory[ValidationNel[Throwable, CSARResults]]({
          cname: String =>
            {
              play.api.Logger.debug("tosca.CSARs findByName: csars:" + csarsName)
              (riak.fetch(csarsName) leftMap { t: NonEmptyList[Throwable] =>
                new ServiceUnavailableError(csarsName, (t.list.map(m => m.getMessage)).mkString("\n"))
              }).toValidationNel.flatMap { xso: Option[GunnySack] =>
                xso match {
                  case Some(xs) => {
                    (Validation.fromTryCatch[models.tosca.CSARResult] {
                      parse(xs.value).extract[CSARResult]
                    } leftMap { t: Throwable =>
                      new ResourceItemNotFound(csarsName, t.getMessage)
                    }).toValidationNel.flatMap { j: CSARResult =>
                      Validation.success[Throwable, CSARResults](nels(j.some)).toValidationNel //screwy kishore, every element in a list ? 
                    }
                  }
                  case None => Validation.failure[Throwable, CSARResults](new ResourceItemNotFound(csarsName, "")).toValidationNel
                }
              }
            }
        }).get(csarsName).eval(InMemoryCache[ValidationNel[Throwable, CSARResults]]())
      }
    } map {
      _.foldRight((CSARResults.empty).successNel[Throwable])(_ +++ _)
    }).head //return the folded element in the head. 

  }

  /*
   * An IO wrapped finder using an email. Upon fetching the account_id for an email, 
   * the csarnames are listed on the index (account.id) in bucket `CSARs`.
   * Using a "csarname" as key, return a list of ValidationNel[List[CSARResult]] 
   * Takes an email, and returns a Future[ValidationNel, List[Option[CSARResult]]]
   */
  def findByEmail(email: String): ValidationNel[Throwable, CSARResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("tosca.CSARs", "findByEmail:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    val res = eitherT[IO, NonEmptyList[Throwable], ValidationNel[Throwable, CSARResults]] {
      (((for {
        aor <- (Accounts.findByEmail(email) leftMap { t: NonEmptyList[Throwable] => t }) //captures failure on the left side, success on right ie the component before the (<-)
      } yield {
        val bindex = ""
        val bvalue = Set("")
        play.api.Logger.debug(("%-20s -->[%s]").format("tosca.CSARs", "findByEmail" + aor.get.id))
        new GunnySack("csar", aor.get.id, RiakConstants.CTYPE_TEXT_UTF8,
          None, Map(metadataKey -> metadataVal), Map((bindex, bvalue))).some
      }) leftMap { t: NonEmptyList[Throwable] => t } flatMap {
        gs: Option[GunnySack] => riak.fetchIndexByValue(gs.get)
      } map { nm: List[String] =>
        (if (!nm.isEmpty) findByName(nm.some) else
          new ResourceItemNotFound(email, "CSAR = nothing found.").failureNel[CSARResults])
      }).disjunction).pure[IO]
    }.run.map(_.validation).unsafePerformIO
    res.getOrElse(new ResourceItemNotFound(email, "CSAR = nothing found.").failureNel[CSARResults])
  }

  implicit val sedimentCSARsResults = new Sedimenter[ValidationNel[Throwable, CSARResults]] {
    def sediment(maybeASediment: ValidationNel[Throwable, CSARResults]): Boolean = {
      val notSed = maybeASediment.isSuccess
      play.api.Logger.debug("%-20s -->[%s]".format("|^/^|-->CSR:sediment:", notSed))
      notSed
    }
  }
  
  implicit val sedimentStrings = new Sedimenter[ValidationNel[Throwable, String]] {
    def sediment(maybeASediment: ValidationNel[Throwable, String]): Boolean = {
      val notSed = maybeASediment.isSuccess
      play.api.Logger.debug("%-20s -->[%s]".format("|^/^|-->CSR:sediment:", notSed))
      notSed
    }
  }

  implicit val sedimentCSARLinksResults = new Sedimenter[ValidationNel[Throwable, CSARLinkResults]] {
    def sediment(maybeASediment: ValidationNel[Throwable, CSARLinkResults]): Boolean = {
      val notSed = maybeASediment.isSuccess
      play.api.Logger.debug("%-20s -->[%s]".format("|^/^|-->CSRLK:sediment:", notSed))
      notSed
    }
  }

}