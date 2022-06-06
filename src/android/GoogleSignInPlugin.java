package com.devapps;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;

import org.apache.cordova.CordovaWebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.android.gms.auth.api.identity.BeginSignInRequest;
import com.google.android.gms.auth.api.identity.BeginSignInResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.SignInClient;
import com.google.android.gms.auth.api.identity.SignInCredential;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.google.firebase.auth.GoogleAuthProvider;

import java.util.Date;

public class GoogleSignInPlugin extends CordovaPlugin {

    private static final int RC_SIGN_IN = 101;
    private static final int RC_ONE_TAP = 102;

    private GoogleSignInAccount account;
    private FirebaseAuth mAuth;

    private SignInClient mOneTapSigninClient;
    private BeginSignInRequest mSiginRequest;

    private Context mContext;
    private Activity mCurrentActivity;
    private CallbackContext mCallbackContext;

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        mCurrentActivity = this.cordova.getActivity();
        mAuth = FirebaseAuth.getInstance();
        mContext = this.cordova.getActivity().getApplicationContext();
        FirebaseApp.initializeApp(mContext);
        checkIfOneTapSignInCoolingPeriodShouldBeReset();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        mCallbackContext = callbackContext;
        if (action.equals(Constants.CORDOVA_ACTION_ONE_TAP_LOGIN)) {
            this.oneTapLogin();
            return true;
        } else if (action.equals(Constants.CORDOVA_ACTION_IS_SIGNEDIN)) {
            this.isSignedIn();
            return true;
        } else if (action.equals(Constants.CORDOVA_ACTION_DISCONNECT)) {
            this.disconnect();
            return true;
        } else if (action.equals(Constants.CORDOVA_ACTION_SIGNIN)) {
            this.signIn();
            return true;
        } else if (action.equals(Constants.CORDOVA_ACTION_SIGNOUT)) {
            this.signOut();
            return true;
        }
        return false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                account = task.getResult(ApiException.class);
                firebaseAuthWithGoogle(account.getIdToken());
            } catch (Exception ex) {
                sendErrorMessage(ex.getMessage());
            }
        } else if (requestCode == RC_ONE_TAP) {
            try {
                SignInCredential credential = mOneTapSigninClient.getSignInCredentialFromIntent(data);
                firebaseAuthWithGoogle(credential.getGoogleIdToken());
            } catch(ApiException ex) {
                String errorMessage = "";
                if (ex.getStatusCode() == CommonStatusCodes.CANCELED) {
                    errorMessage = "One Tap Signin was denied by the user.";
                    beginOneTapSigninCoolingPeriod();
                } else {
                    errorMessage = ex.getLocalizedMessage();
                }
                sendErrorMessage(errorMessage);
            } catch (Exception ex) {
                sendErrorMessage(ex.getMessage());
            }
        }
    }

    private void isSignedIn() {
        boolean isSignedIn = (account != null || mAuth.getCurrentUser() != null);
        sendSuccessMessage(isSignedIn);
    }

    private void disconnect() {
        sendErrorMessage("Not available on Android.");
    }

    private void signIn() {
        cordova.setActivityResultCallback(this);
        GoogleSignInOptions gso = getGoogleSignInOptions();
        GoogleSignInClient mGoogleSignInClient = GoogleSignIn.getClient(mContext, gso);
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        mCurrentActivity.startActivityForResult(signInIntent, RC_SIGN_IN);
    }

    private void oneTapLogin() {
        checkIfOneTapSignInCoolingPeriodShouldBeReset();
        SharedPreferences sharedPreferences = getSharedPreferences();
        boolean shouldShowOneTapUI = sharedPreferences.getBoolean(Constants.PREF_SHOW_ONE_TAP_UI, true);

        if(shouldShowOneTapUI) {
            cordova.setActivityResultCallback(this);
            mOneTapSigninClient = Identity.getSignInClient(mContext);
            mSiginRequest = BeginSignInRequest.builder()
                    .setPasswordRequestOptions(BeginSignInRequest.PasswordRequestOptions.builder().build())
                    .setGoogleIdTokenRequestOptions(BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                            .setSupported(true)
                            .setFilterByAuthorizedAccounts(false)
                            .setServerClientId(this.cordova.getActivity().getResources().getString(getAppResource("default_client_id", "string")))
                            .build())
                    .setAutoSelectEnabled(true)
                    .build();

            mOneTapSigninClient.beginSignIn(mSiginRequest)
                    .addOnSuccessListener(new OnSuccessListener<BeginSignInResult>() {
                        @Override
                        public void onSuccess(BeginSignInResult beginSignInResult) {
                            try {
                                mCurrentActivity.startIntentSenderForResult(beginSignInResult.getPendingIntent().getIntentSender(), RC_ONE_TAP, null, 0, 0, 0);
                            } catch (IntentSender.SendIntentException ex) {
                                ex.printStackTrace();
                                sendErrorMessage(ex.getMessage());
                            }
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception ex) {
                            sendErrorMessage(ex.getMessage());
                        }
                    });
        } else {
            sendErrorMessage("One Tap Signin was denied by the user.");
        }
    }

    private void signOut() {
        GoogleSignInOptions gso = getGoogleSignInOptions();

        GoogleSignInClient mGoogleSignInClient = GoogleSignIn.getClient(mContext, gso);
        mGoogleSignInClient.signOut().addOnCompleteListener(new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull Task<Void> task) {
                account = null;
                sendSuccessMessage("Logged out");
                mAuth.signOut();
            }
        });
        mGoogleSignInClient.signOut().addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception ex) {
                sendErrorMessage(ex.getMessage());
            }
        });
    }

    private void firebaseAuthWithGoogle(String googleIdToken) {
        AuthCredential credentials = GoogleAuthProvider.getCredential(googleIdToken, null);
        mAuth.signInWithCredential(credentials).addOnCompleteListener(new OnCompleteListener<AuthResult>() {
            @Override
            public void onComplete(@NonNull Task<AuthResult> task) {

                if(task.isSuccessful()) {
                    FirebaseUser user = mAuth.getCurrentUser();
                    user.getIdToken(false).addOnSuccessListener(new OnSuccessListener<GetTokenResult>() {
                        @Override
                        public void onSuccess(GetTokenResult getTokenResult) {
                            try {
                                JSONObject userInfo = new JSONObject();
                                userInfo.put("id", user.getUid());
                                userInfo.put("display_name", user.getDisplayName());
                                userInfo.put("email", user.getEmail());
                                userInfo.put("photo_url", user.getPhotoUrl());
                                userInfo.put("id_token", getTokenResult.getToken());
                                userInfo.put("json_web_token", googleIdToken);
                                sendSuccessMessage(userInfo);
                            } catch (Exception ex) {
                                sendErrorMessage(ex.getMessage());
                            }
                        }
                    }).addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception ex) {
                            sendErrorMessage(ex.getMessage());
                        }
                    });
                }
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception ex) {
                sendErrorMessage(ex.getMessage());
            }
        });
    }

    private GoogleSignInOptions getGoogleSignInOptions() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(this.cordova.getActivity().getResources().getString(getAppResource("default_client_id", "string")))
                .requestEmail()
                .build();
        return gso;
    }

    private int getAppResource(String name, String type) {
        return cordova.getActivity().getResources().getIdentifier(name, type, cordova.getActivity().getPackageName());
    }

    private void beginOneTapSigninCoolingPeriod() {
        SharedPreferences sharedPreferences = getSharedPreferences();
        SharedPreferences.Editor preferences = sharedPreferences.edit();
        preferences.putBoolean(Constants.PREF_SHOW_ONE_TAP_UI, false);
        preferences.putLong(Constants.PREF_COOLING_START_TIME, new Date().getTime());
        preferences.apply();
    }

    private void checkIfOneTapSignInCoolingPeriodShouldBeReset() {
        SharedPreferences sharedPreferences = getSharedPreferences();
        Date now = new Date();
        long coolingStartTime = sharedPreferences.getLong(Constants.PREF_COOLING_START_TIME, now.getTime());

        int daysApart = (int)((now.getTime() - coolingStartTime) / (1000*60*60*24l));
        if(daysApart >= 1) {
            SharedPreferences.Editor preferences = sharedPreferences.edit();
            preferences.putBoolean(Constants.PREF_SHOW_ONE_TAP_UI, true);
            preferences.putLong(Constants.PREF_COOLING_START_TIME, 0L);
            preferences.apply();
        }
    }

    private void sendSuccessMessage(Object data) {
        try {
            JSONObject response = new JSONObject();
            response.put(Constants.JSON_STATUS, Constants.JSON_SUCCESS);
            response.put(Constants.JSON_MESSAGE, data);
            mCallbackContext.success(response);
        } catch (Exception e) {
            e.printStackTrace();
            sendErrorMessage(e.getMessage());
        }
    }

    private void sendErrorMessage(String message) {
        try {
            JSONObject response = new JSONObject();
            response.put(Constants.JSON_STATUS, Constants.JSON_ERROR);
            response.put(Constants.JSON_MESSAGE, message);
            mCallbackContext.error(response);
        } catch (Exception e) {
            try {
                JSONObject response = new JSONObject();
                response.put(Constants.JSON_STATUS, Constants.JSON_ERROR);
                response.put(Constants.JSON_MESSAGE, e.getMessage());
                mCallbackContext.error(response);
            } catch (Exception ignored) {

            }
        }
    }

    private SharedPreferences getSharedPreferences() {
        return mContext.getSharedPreferences(Constants.PREF_FILENAME, Context.MODE_PRIVATE);
    }
}