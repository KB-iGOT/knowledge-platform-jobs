package org.sunbird.job.util

import java.sql.Timestamp

case class UserDashState(
                          id: String,
                          orgId: String,
                          contentType: String,
                          typeIdentifier: String,
                          userId: String,
                          status: String,
                          updatedDate: Timestamp,
                          enrolledDate: Timestamp,
                          createdDate: Timestamp
                        )
