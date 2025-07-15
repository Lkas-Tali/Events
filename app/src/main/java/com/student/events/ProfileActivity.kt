package com.student.events

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.student.events.adapters.EventsAdapter
import com.student.events.databinding.ActivityProfileBinding
import com.student.events.models.Event
import java.text.SimpleDateFormat
import java.util.*

class ProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var eventsAdapter: EventsAdapter
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    private val organizedEvents = mutableListOf<Event>()
    private val attendingEvents = mutableListOf<Event>()

    private var currentTab = 0 // 0: Organized, 1: Attending (NO MORE PAST)
    private var currentUserId: String? = null

    companion object {
        private const val TAG = "ProfileActivity"

        fun newIntent(context: Context): Intent {
            return Intent(context, ProfileActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Initialize Firebase
            auth = FirebaseAuth.getInstance()
            database = FirebaseDatabase.getInstance()
            currentUserId = auth.currentUser?.uid

            if (currentUserId == null) {
                Log.e(TAG, "No current user found")
                finish()
                return
            }

            // Initialize data binding
            binding = DataBindingUtil.setContentView(this, R.layout.activity_profile)

            Log.d(TAG, "ProfileActivity created successfully")

            setupViews()
            setupRecyclerView()
            setupTabLayout()
            loadUserProfile()
            loadUserEvents()

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            finish() // Close activity if there's an initialization error
        }
    }

    private fun setupViews() {
        try {
            // Back button
            binding.backButton.setOnClickListener {
                finish()
            }

            // Profile action buttons
            binding.editProfileButton.setOnClickListener {
                // TODO: Implement edit profile functionality
                Log.d(TAG, "Edit Profile clicked")
            }

            binding.changePasswordButton.setOnClickListener {
                // TODO: Implement change password functionality
                Log.d(TAG, "Change Password clicked")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up views: ${e.message}", e)
        }
    }

    private fun setupRecyclerView() {
        try {
            eventsAdapter = EventsAdapter(
                events = emptyList(),
                currentUserId = currentUserId ?: "",
                onEventClick = { event ->
                    Log.d(TAG, "Event clicked: ${event.title}")
                    showCustomEventDetails(event)
                },
                onEditClick = { event ->
                    Log.d(TAG, "Edit event clicked: ${event.title}")
                    // TODO: Navigate to edit event
                },
                onCancelClick = { event ->
                    Log.d(TAG, "Cancel event clicked: ${event.title}")
                    // TODO: Handle cancel event
                },
                onRsvpClick = { event ->
                    Log.d(TAG, "RSVP clicked: ${event.title}")
                    // TODO: Handle RSVP
                },
                onCancelRsvpClick = { event ->
                    Log.d(TAG, "Cancel RSVP clicked: ${event.title}")
                    // TODO: Handle cancel RSVP
                }
            )

            binding.profileEventsRecyclerView.apply {
                layoutManager = LinearLayoutManager(this@ProfileActivity)
                adapter = eventsAdapter
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up RecyclerView: ${e.message}", e)
        }
    }

    private fun setupTabLayout() {
        try {
            binding.eventsTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    tab?.let {
                        currentTab = it.position
                        Log.d(TAG, "Tab selected: $currentTab")
                        updateEventsForTab(currentTab)
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up TabLayout: ${e.message}", e)
        }
    }

    private fun loadUserProfile() {
        try {
            currentUserId?.let { uid ->
                database.reference.child("users").child(uid)
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            try {
                                val fullName = snapshot.child("fullName").getValue(String::class.java)
                                val email = snapshot.child("email").getValue(String::class.java)
                                val about = snapshot.child("about").getValue(String::class.java)
                                val profileImageUrl = snapshot.child("profileImageUrl").getValue(String::class.java)

                                Log.d(TAG, "Loaded user profile: $fullName")

                                // Update UI
                                binding.apply {
                                    profileNameText.text = fullName ?: "User"
                                    profileEmailText.text = email ?: ""
                                    profileAboutText.text = about ?: "Welcome to my profile!"
                                }

                                // Load profile image
                                if (!profileImageUrl.isNullOrEmpty()) {
                                    Glide.with(this@ProfileActivity)
                                        .load(profileImageUrl)
                                        .placeholder(R.drawable.ic_person)
                                        .error(R.drawable.ic_person)
                                        .circleCrop()
                                        .into(binding.profileImageView)
                                } else {
                                    binding.profileImageView.setImageResource(R.drawable.ic_person)
                                }

                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing user profile data: ${e.message}", e)
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Failed to load user profile: ${error.message}")
                            // Set fallback data
                            binding.apply {
                                profileNameText.text = auth.currentUser?.displayName ?: "User"
                                profileEmailText.text = auth.currentUser?.email ?: ""
                                profileAboutText.text = "Welcome to my profile!"
                                profileImageView.setImageResource(R.drawable.ic_person)
                            }
                        }
                    })
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading user profile: ${e.message}", e)
        }
    }

    private fun loadUserEvents() {
        try {
            currentUserId?.let { uid ->
                // Load all events
                database.reference.child("events")
                    .addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(eventsSnapshot: DataSnapshot) {
                            try {
                                organizedEvents.clear()
                                attendingEvents.clear()

                                for (eventSnapshot in eventsSnapshot.children) {
                                    val event = eventSnapshot.getValue(Event::class.java)
                                    event?.let {
                                        it.id = eventSnapshot.key ?: ""

                                        // SIMPLE LOGIC: Only Organized vs Attending (NO PAST EVENTS)
                                        if (it.organizer?.uid == uid) {
                                            // User organized this event
                                            organizedEvents.add(it)
                                            Log.d(TAG, "Added to organized: ${it.title}")
                                        } else if (it.attendees.containsKey(uid)) {
                                            // User is attending this event (NOT organizing)
                                            attendingEvents.add(it)
                                            Log.d(TAG, "Added to attending: ${it.title}")
                                        }
                                    }
                                }

                                // Sort events by date (newest first)
                                organizedEvents.sortBy { it.dateTime?.seconds ?: 0 }
                                attendingEvents.sortBy { it.dateTime?.seconds ?: 0 }

                                Log.d(TAG, "Final counts - Organized: ${organizedEvents.size}, Attending: ${attendingEvents.size}")

                                // Update tab counts
                                updateTabCounts()

                                // Update current tab
                                updateEventsForTab(currentTab)

                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing events data: ${e.message}", e)
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            Log.e(TAG, "Failed to load events: ${error.message}")
                        }
                    })
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading user events: ${e.message}", e)
        }
    }

    private fun updateTabCounts() {
        try {
            val tabLayout = binding.eventsTabLayout
            // Only 2 tabs now: Organized and Attending
            tabLayout.getTabAt(0)?.text = "Organized (${organizedEvents.size})"
            tabLayout.getTabAt(1)?.text = "Attending (${attendingEvents.size})"
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tab counts: ${e.message}", e)
        }
    }

    private fun updateEventsForTab(tabPosition: Int) {
        try {
            val filteredEvents = when (tabPosition) {
                0 -> organizedEvents  // Organized tab
                1 -> attendingEvents  // Attending tab
                else -> emptyList()
            }

            Log.d(TAG, "Updating events for tab $tabPosition, count: ${filteredEvents.size}")

            if (filteredEvents.isEmpty()) {
                binding.emptyStateLayout.visibility = View.VISIBLE
                binding.profileEventsRecyclerView.visibility = View.GONE

                // Update empty state text based on tab
                val emptyText = when (tabPosition) {
                    0 -> "You haven't organized any events yet."
                    1 -> "You are not attending any events."
                    else -> "No events available."
                }
                binding.emptyStateText.text = emptyText
            } else {
                binding.emptyStateLayout.visibility = View.GONE
                binding.profileEventsRecyclerView.visibility = View.VISIBLE
                eventsAdapter.updateEvents(filteredEvents)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error updating events for tab: ${e.message}", e)
        }
    }

    // ========================================
    // EXACT SAME POPUP SYSTEM AS MAINACTIVITY
    // ========================================

    private fun showCustomEventDetails(event: Event) {
        setMainContentInteraction(false)
        val detailsView = LayoutInflater.from(this).inflate(R.layout.dialog_event_details, binding.eventDetailsContainer, false)
        populateDetailsView(detailsView, event)
        binding.eventDetailsContainer.addView(detailsView)

        binding.darkScrim.visibility = View.VISIBLE
        animateDetailsIn(detailsView)
    }

    private fun populateDetailsView(view: View, event: Event) {
        view.findViewById<TextView>(R.id.eventTitle).text = event.title
        view.findViewById<ImageView>(R.id.closeButton).setOnClickListener {
            animateDetailsOut(view)
        }

        val imageView = view.findViewById<ImageView>(R.id.eventImage)
        if (!event.imageUrl.isNullOrEmpty()) {
            imageView.visibility = View.VISIBLE
            Glide.with(this).load(event.imageUrl).into(imageView)
        } else {
            imageView.visibility = View.GONE
        }

        view.findViewById<TextView>(R.id.dateTimeText).text = formatDateTime(event)
        view.findViewById<TextView>(R.id.locationText).text = event.location
        view.findViewById<TextView>(R.id.descriptionText).text = event.description
        view.findViewById<TextView>(R.id.attendeesText).text = "${event.attendeesCount} people attending"
        view.findViewById<TextView>(R.id.organizerText).text = "Organized by ${event.organizer?.fullName ?: "Unknown"}"
    }

    private fun animateDetailsIn(detailsView: View) {
        binding.eventDetailsContainer.visibility = View.VISIBLE

        val scrimFadeIn = ObjectAnimator.ofFloat(binding.darkScrim, "alpha", 1f)
        scrimFadeIn.duration = 400

        detailsView.alpha = 0f
        detailsView.scaleX = 0.8f
        detailsView.scaleY = 0.8f

        val cardFadeIn = ObjectAnimator.ofFloat(detailsView, "alpha", 1f)
        val cardScaleX = ObjectAnimator.ofFloat(detailsView, "scaleX", 1f)
        val cardScaleY = ObjectAnimator.ofFloat(detailsView, "scaleY", 1f)

        val cardAnimatorSet = AnimatorSet()
        cardAnimatorSet.playTogether(cardFadeIn, cardScaleX, cardScaleY)
        cardAnimatorSet.interpolator = OvershootInterpolator(1.1f)
        cardAnimatorSet.duration = 500

        val contentContainer = detailsView.findViewById<ViewGroup>(R.id.contentContainer)
        val contentAnimators = AnimatorSet()
        val animators = mutableListOf<Animator>()

        for (i in 0 until contentContainer.childCount) {
            val child = contentContainer.getChildAt(i)
            child.alpha = 0f
            child.translationY = 80f

            val childFade = ObjectAnimator.ofFloat(child, "alpha", 1f)
            val childSlide = ObjectAnimator.ofFloat(child, "translationY", 0f)

            val childSet = AnimatorSet()
            childSet.playTogether(childFade, childSlide)
            childSet.duration = 400
            childSet.interpolator = DecelerateInterpolator()
            childSet.startDelay = (i * 60).toLong()
            animators.add(childSet)
        }
        contentAnimators.playTogether(animators)

        val finalAnimatorSet = AnimatorSet()
        finalAnimatorSet.play(scrimFadeIn).with(cardAnimatorSet).before(contentAnimators)
        finalAnimatorSet.start()
    }

    private fun animateDetailsOut(detailsView: View) {
        val scrimFadeOut = ObjectAnimator.ofFloat(binding.darkScrim, "alpha", 0f)
        scrimFadeOut.duration = 300

        val cardFadeOut = ObjectAnimator.ofFloat(detailsView, "alpha", 0f)
        val cardSlideDown = ObjectAnimator.ofFloat(detailsView, "translationY", 100f)

        val cardAnimatorSet = AnimatorSet()
        cardAnimatorSet.playTogether(cardFadeOut, cardSlideDown)
        cardAnimatorSet.interpolator = DecelerateInterpolator()
        cardAnimatorSet.duration = 300

        val finalAnimatorSet = AnimatorSet()
        finalAnimatorSet.playTogether(scrimFadeOut, cardAnimatorSet)
        finalAnimatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.darkScrim.visibility = View.GONE
                binding.eventDetailsContainer.visibility = View.GONE
                binding.eventDetailsContainer.removeView(detailsView)
                setMainContentInteraction(true)
            }
        })
        finalAnimatorSet.start()
    }

    private fun setMainContentInteraction(enabled: Boolean) {
        fun setViewAndChildrenEnabled(view: View, enabled: Boolean) {
            view.isEnabled = enabled
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    setViewAndChildrenEnabled(child, enabled)
                }
            }
        }

        // Find the main content view (everything except the popup)
        val mainContent = findViewById<View>(R.id.mainContent) ?: return
        setViewAndChildrenEnabled(mainContent, enabled)
    }

    private fun formatDateTime(event: Event): String {
        event.dateTime?.seconds?.let {
            val date = Date(it * 1000)
            val displayFormat = SimpleDateFormat("EEEE, d MMMM HH:mm", Locale.UK)
            return displayFormat.format(date)
        }
        return "Date and time not specified"
    }
}