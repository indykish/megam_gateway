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
package models

import com.stackmob.scaliak._
import controllers.stack._
import org.megam.common.riak.GSRiak

/**
 * @author ram
 *
 */
package object riak {
  
    val GatewayScaliakPool = Scaliak.clientPool(List(MConfig.riakurl))
    
    def GWRiak(bucketName: String) = new GSRiak(MConfig.riakurl, bucketName)(GatewayScaliakPool)


}