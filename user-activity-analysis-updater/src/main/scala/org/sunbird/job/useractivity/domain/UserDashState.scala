package org.sunbird.job.useractivity.domain

import java.sql.Timestamp

/**
 * Represents the state of a user's dashboard.
 *
 * @param id             Unique identifier for the user dashboard state.
 * @param orgId          Identifier for the organization associated with the user.
 * @param contentType    Type of content being displayed on the dashboard (e.g., articles, videos).
 * @param typeIdentifier Specific identifier for the type of the dashboard item.
 * @param userId         Identifier for the user associated with this dashboard state.
 * @param status         Current status of the dashboard (e.g., active, inactive).
 * @param updatedDate    Timestamp indicating when the dashboard state was last updated.
 * @param enrolledDate   Timestamp indicating when the user was enrolled in this state.
 * @param createdDate    Timestamp indicating when this dashboard state was created.
 */
case class UserDashState(
                          id: String,
                          orgId: String,
                          contentType: String,
                          typeIdentifier: String,
                          userId: String,
                          batchId: String,
                          status: String,
                          updatedDate: Timestamp,
                          enrolledDate: Timestamp,
                          createdDate: Timestamp
                        )
